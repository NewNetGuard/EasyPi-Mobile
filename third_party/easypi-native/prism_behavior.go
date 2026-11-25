package easypinative

import (
	"encoding/binary"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

// prismBehaviorGuard correlates an unmistakably advertising DNS lookup with
// the short-lived third-party TLS chain that immediately surrounds it. It
// never blocks the current first-party site and it does not decrypt TLS.
var prismBehaviorGuard = struct {
	sync.RWMutex
	owners map[string]*prismBehaviorOwner
	flows  map[prismFlowKey]prismBehaviorFlow
	pruned time.Time
}{owners: make(map[string]*prismBehaviorOwner), flows: make(map[prismFlowKey]prismBehaviorFlow)}

const prismBehaviorTouchInterval = 5 * time.Second
const prismBehaviorPruneInterval = 5 * time.Second

type prismBehaviorOwner struct {
	primaryRoot string
	primarySeen time.Time
	burstUntil  time.Time
}

type prismBehaviorFlow struct {
	owner    string
	root     string
	lastSeen time.Time
}

// Established TLS flows are checked for every application packet and every
// download ACK. Keep an immutable read view so the packet pump does not take a
// shared mutex for the common case. Writers publish a complete view while
// holding prismBehaviorGuard, so an advertising burst still becomes visible
// atomically before the triggering DNS packet returns.
type prismBehaviorSnapshot struct {
	owners map[string]prismBehaviorOwner
	flows  map[prismFlowKey]prismBehaviorFlow
}

var prismBehaviorView atomic.Pointer[prismBehaviorSnapshot]

func publishPrismBehaviorLocked() {
	view := &prismBehaviorSnapshot{
		owners: make(map[string]prismBehaviorOwner, len(prismBehaviorGuard.owners)),
		flows:  make(map[prismFlowKey]prismBehaviorFlow, len(prismBehaviorGuard.flows)),
	}
	for owner, state := range prismBehaviorGuard.owners {
		if state != nil {
			view.owners[owner] = *state
		}
	}
	for flow, state := range prismBehaviorGuard.flows {
		view.flows[flow] = state
	}
	prismBehaviorView.Store(view)
}

// PrismFlowOwnerResolver lets the Android VpnService attribute a TUN tuple to
// the app that owns it. The behavior guard fails open when Android cannot
// resolve an owner, so one app's advertising activity never affects another.
type PrismFlowOwnerResolver interface {
	Owner(protocol string, clientIP string, clientPort int, serverIP string, serverPort int) string
}

// PrismDomainPolicyResolver lets Android apply the EasyPi policy received over
// Mobile Link without sending the user's payload through the home server. The
// callback must be non-blocking; Android returns a cached decision and refreshes
// unknown domains asynchronously over HTTPS.
type PrismDomainPolicyResolver interface {
	ShouldBlock(domain string) bool
	CheckBlock(domain string) bool
}

// PrismBlockObserver receives deduplicated events only after the packet filter
// has actually decided to drop a DNS or TLS request.
type PrismBlockObserver interface {
	OnQuery(domain string)
	OnBlocked(domain string, reason string)
	// OnDNSResult reports each observed DNS request exactly once, independently
	// of deduplicated TLS/block notifications and policy-cache activity.
	OnDNSResult(domain string, blocked bool, occurredAtMillis int64)
}

var prismFlowOwner = struct {
	sync.RWMutex
	resolver PrismFlowOwnerResolver
}{}

var prismDomainPolicy = struct {
	sync.RWMutex
	resolver PrismDomainPolicyResolver
}{}

var prismBlockObserver = struct {
	sync.RWMutex
	observer PrismBlockObserver
}{}

type prismBlockEventKey struct {
	flow   prismFlowKey
	domain string
}

var prismBlockEvents = struct {
	sync.Mutex
	values map[prismBlockEventKey]time.Time
}{values: make(map[prismBlockEventKey]time.Time)}

func SetPrismFlowOwnerResolver(resolver PrismFlowOwnerResolver) {
	prismFlowOwner.Lock()
	prismFlowOwner.resolver = resolver
	prismFlowOwner.Unlock()
}

func ClearPrismFlowOwnerResolver() {
	prismFlowOwner.Lock()
	prismFlowOwner.resolver = nil
	prismFlowOwner.Unlock()
}

func SetPrismDomainPolicyResolver(resolver PrismDomainPolicyResolver) {
	prismDomainPolicy.Lock()
	prismDomainPolicy.resolver = resolver
	prismDomainPolicy.Unlock()
}

func ClearPrismDomainPolicyResolver() {
	prismDomainPolicy.Lock()
	prismDomainPolicy.resolver = nil
	prismDomainPolicy.Unlock()
}

func SetPrismBlockObserver(observer PrismBlockObserver) {
	prismBlockObserver.Lock()
	prismBlockObserver.observer = observer
	prismBlockObserver.Unlock()
}

func ClearPrismBlockObserver() {
	prismBlockObserver.Lock()
	prismBlockObserver.observer = nil
	prismBlockObserver.Unlock()
	prismBlockEvents.Lock()
	clear(prismBlockEvents.values)
	prismBlockEvents.Unlock()
}

func clearPrismBehaviorState() {
	clearPrismTLS()
	prismBehaviorGuard.Lock()
	clear(prismBehaviorGuard.owners)
	clear(prismBehaviorGuard.flows)
	prismBehaviorGuard.pruned = time.Time{}
	publishPrismBehaviorLocked()
	prismBehaviorGuard.Unlock()
	prismBlockEvents.Lock()
	clear(prismBlockEvents.values)
	prismBlockEvents.Unlock()
}

func prismBehaviorShouldDrop(packet []byte, now time.Time) bool {
	return prismBehaviorShouldDropWithRelease(packet, now, nil)
}

func prismBehaviorShouldDropWithRelease(packet []byte, now time.Time, release func([]byte)) bool {
	if prismUninspectablePacket(packet) {
		return true
	}
	flow, payload, ok := parsePrismTransport(packet)
	return prismBehaviorShouldDropParsed(packet, flow, payload, ok, now, release)
}

func prismBehaviorShouldDropParsed(packet []byte, flow prismFlowKey, payload []byte, ok bool, now time.Time, release func([]byte)) bool {
	if !ok {
		return false
	}
	if prismTLSPolicyDropWithRelease(packet, flow, payload, now, release) {
		return true
	}

	var domain string
	if flow.protocol == 17 && flow.destPort == 53 {
		domain, _ = parseDNSQuestion(payload)
	}
	var host string
	if flow.protocol == 6 && flow.destPort == 443 {
		host, _ = parseTLSClientHelloSNI(payload)
	}
	if domain != "" && prismDomainPolicyShouldDrop(domain) {
		prismReportBlocked(flow, domain, "policy", now)
		return true
	}
	if domain == "" && host == "" && (flow.protocol != 6 || flow.destPort != 443) {
		return false
	}
	if domain == "" && host == "" {
		return prismEstablishedBehaviorShouldDrop(flow, now)
	}

	owner := ""
	if domain != "" || host != "" {
		owner = resolvePrismFlowOwner(flow)
	}

	prismBehaviorGuard.Lock()
	defer prismBehaviorGuard.Unlock()
	prunePrismBehaviorLocked(now)

	if domain != "" {
		if owner != "" && looksLikeAdvertisingHost(domain) {
			state := prismBehaviorOwnerLocked(owner)
			state.burstUntil = now.Add(30 * time.Second)
		}
		publishPrismBehaviorLocked()
		return false
	}

	if host != "" && owner != "" {
		root := registrableRoot(host)
		state := prismBehaviorOwnerLocked(owner)
		if state.primaryRoot == "" || isPrimaryNavigationHost(host, root, state.primaryRoot) {
			state.primaryRoot = root
			// A new conventional web host is a user navigation. End any
			// advertising burst inherited from the previous page so the new
			// document cannot be mistaken for a companion ad connection.
			state.burstUntil = time.Time{}
		}
		prismBehaviorGuard.flows[flow] = prismBehaviorFlow{owner: owner, root: root, lastSeen: now}
		if root == state.primaryRoot {
			state.primarySeen = now
		}
	}

	known, found := prismBehaviorGuard.flows[flow]
	if found {
		known.lastSeen = now
		prismBehaviorGuard.flows[flow] = known
	}
	if !found || known.owner == "" {
		return false
	}
	state := prismBehaviorGuard.owners[known.owner]
	blocked := state != nil && now.Before(state.burstUntil) &&
		state.primaryRoot != "" && known.root != "" && known.root != state.primaryRoot
	publishPrismBehaviorLocked()
	if blocked {
		prismReportBlocked(flow, host, "behavior", now)
	}
	return blocked
}

func prismEstablishedBehaviorShouldDrop(flow prismFlowKey, now time.Time) bool {
	view := prismBehaviorView.Load()
	if view == nil {
		return false
	}
	known, found := view.flows[flow]
	if !found || known.owner == "" {
		return false
	}
	state, hasOwner := view.owners[known.owner]
	blocked := hasOwner && now.Before(state.burstUntil) &&
		state.primaryRoot != "" && known.root != "" && known.root != state.primaryRoot
	needsTouch := now.Sub(known.lastSeen) >= prismBehaviorTouchInterval

	if needsTouch {
		prismBehaviorGuard.Lock()
		if current, ok := prismBehaviorGuard.flows[flow]; ok && now.Sub(current.lastSeen) >= prismBehaviorTouchInterval {
			current.lastSeen = now
			prismBehaviorGuard.flows[flow] = current
		}
		prunePrismBehaviorLocked(now)
		publishPrismBehaviorLocked()
		prismBehaviorGuard.Unlock()
	}
	if blocked {
		prismReportBlocked(flow, "", "behavior", now)
	}
	return blocked
}

func prismReportQuery(domain string) {
	prismBlockObserver.RLock()
	observer := prismBlockObserver.observer
	prismBlockObserver.RUnlock()
	if observer != nil {
		observer.OnQuery(domain)
	}
}

func prismReportDNSResult(domain string, blocked bool, occurredAt time.Time) {
	prismBlockObserver.RLock()
	observer := prismBlockObserver.observer
	prismBlockObserver.RUnlock()
	if observer != nil {
		observer.OnDNSResult(domain, blocked, occurredAt.UnixMilli())
	}
}

func prismReportBlocked(flow prismFlowKey, domain, reason string, now time.Time) {
	key := prismBlockEventKey{flow: flow, domain: domain}
	prismBlockEvents.Lock()
	for current, reportedAt := range prismBlockEvents.values {
		if now.Sub(reportedAt) > 10*time.Second {
			delete(prismBlockEvents.values, current)
		}
	}
	if reportedAt, found := prismBlockEvents.values[key]; found && now.Sub(reportedAt) <= 5*time.Second {
		prismBlockEvents.Unlock()
		return
	}
	prismBlockEvents.values[key] = now
	prismBlockEvents.Unlock()

	prismBlockObserver.RLock()
	observer := prismBlockObserver.observer
	prismBlockObserver.RUnlock()
	if observer != nil {
		observer.OnBlocked(domain, reason)
	}
}

func prismDomainPolicyShouldDrop(domain string) bool {
	prismDomainPolicy.RLock()
	resolver := prismDomainPolicy.resolver
	prismDomainPolicy.RUnlock()
	return resolver != nil && resolver.ShouldBlock(strings.ToLower(strings.Trim(domain, ".")))
}

func prismDomainPolicyCheck(domain string) bool {
	prismDomainPolicy.RLock()
	resolver := prismDomainPolicy.resolver
	prismDomainPolicy.RUnlock()
	return resolver == nil || resolver.CheckBlock(strings.ToLower(strings.Trim(domain, ".")))
}

func isPrimaryNavigationHost(host, root, currentRoot string) bool {
	host = strings.ToLower(strings.TrimSuffix(host, "."))
	return root != "" && root != currentRoot && strings.HasPrefix(host, "www.") &&
		!looksLikeAdvertisingHost(host)
}

func prunePrismBehaviorLocked(now time.Time) {
	if !prismBehaviorGuard.pruned.IsZero() && now.Sub(prismBehaviorGuard.pruned) < prismBehaviorPruneInterval {
		return
	}
	prismBehaviorGuard.pruned = now
	for key, value := range prismBehaviorGuard.flows {
		if now.Sub(value.lastSeen) > 2*time.Minute {
			delete(prismBehaviorGuard.flows, key)
		}
	}
	for owner, state := range prismBehaviorGuard.owners {
		if !state.primarySeen.IsZero() && now.Sub(state.primarySeen) > 2*time.Minute {
			delete(prismBehaviorGuard.owners, owner)
		}
	}
}

func prismBehaviorOwnerLocked(owner string) *prismBehaviorOwner {
	state := prismBehaviorGuard.owners[owner]
	if state == nil {
		state = &prismBehaviorOwner{}
		prismBehaviorGuard.owners[owner] = state
	}
	return state
}

func resolvePrismFlowOwner(flow prismFlowKey) string {
	prismFlowOwner.RLock()
	resolver := prismFlowOwner.resolver
	prismFlowOwner.RUnlock()
	if resolver == nil {
		return ""
	}
	protocol := "udp"
	if flow.protocol == 6 {
		protocol = "tcp"
	}
	return resolver.Owner(
		protocol,
		flow.source.String(),
		int(flow.sourcePort),
		flow.destination.String(),
		int(flow.destPort),
	)
}

func looksLikeAdvertisingHost(host string) bool {
	for _, label := range strings.Split(strings.ToLower(strings.TrimSuffix(host, ".")), ".") {
		switch label {
		case "ad", "ads", "adfox", "advert", "advertising", "banner", "banners", "bidder", "doubleclick",
			"googleadservices", "googlesyndication", "googletagmanager", "rtb", "setupcmp", "tracker", "tracking":
			return true
		}
		if strings.HasPrefix(label, "ad-") || strings.HasPrefix(label, "ads-") ||
			strings.HasPrefix(label, "hb-") || strings.HasSuffix(label, "-ads") {
			return true
		}
	}
	return false
}

func registrableRoot(host string) string {
	labels := strings.Split(strings.Trim(strings.ToLower(host), "."), ".")
	if len(labels) < 2 {
		return strings.Join(labels, ".")
	}
	return strings.Join(labels[len(labels)-2:], ".")
}

func parsePrismTransport(packet []byte) (prismFlowKey, []byte, bool) {
	flow, ok := parsePrismFlow(packet)
	if !ok {
		return prismFlowKey{}, nil, false
	}
	payload, ok := prismTransportPayload(packet, flow)
	return flow, payload, ok
}

func prismTransportPayload(packet []byte, flow prismFlowKey) ([]byte, bool) {
	var offset int
	switch packet[0] >> 4 {
	case 4:
		offset = int(packet[0]&0x0f) * 4
	case 6:
		offset = 40
	default:
		return nil, false
	}
	if flow.protocol == 17 {
		if len(packet) < offset+8 {
			return nil, false
		}
		return packet[offset+8:], true
	}
	if len(packet) < offset+20 {
		return nil, false
	}
	tcpHeader := int(packet[offset+12]>>4) * 4
	if tcpHeader < 20 || len(packet) < offset+tcpHeader {
		return nil, false
	}
	return packet[offset+tcpHeader:], true
}

func parseDNSQuestion(payload []byte) (string, bool) {
	if len(payload) < 17 || binary.BigEndian.Uint16(payload[4:6]) < 1 {
		return "", false
	}
	offset := 12
	labels := make([]string, 0, 4)
	for offset < len(payload) {
		size := int(payload[offset])
		offset++
		if size == 0 {
			break
		}
		if size > 63 || offset+size > len(payload) {
			return "", false
		}
		labels = append(labels, string(payload[offset:offset+size]))
		offset += size
	}
	if len(labels) == 0 || offset+4 > len(payload) {
		return "", false
	}
	return strings.ToLower(strings.Join(labels, ".")), true
}

func parseTLSClientHelloSNI(payload []byte) (string, bool) {
	if len(payload) < 9 || payload[0] != 22 || payload[5] != 1 {
		return "", false
	}
	recordLength := int(binary.BigEndian.Uint16(payload[3:5]))
	recordEnd := recordLength + 5
	if recordEnd > len(payload) {
		// A large ClientHello commonly spans multiple TCP segments. SNI is
		// near the beginning, so parse the complete extensions available in
		// this first segment and fail open if SNI itself is fragmented.
		recordEnd = len(payload)
	}
	pos := 9 + 2 + 32
	if pos >= len(payload) {
		return "", false
	}
	sessionLength := int(payload[pos])
	pos += 1 + sessionLength
	if pos+2 > len(payload) {
		return "", false
	}
	cipherLength := int(binary.BigEndian.Uint16(payload[pos : pos+2]))
	pos += 2 + cipherLength
	if pos >= len(payload) {
		return "", false
	}
	compressionLength := int(payload[pos])
	pos += 1 + compressionLength
	if pos+2 > len(payload) {
		return "", false
	}
	extensionsLength := int(binary.BigEndian.Uint16(payload[pos : pos+2]))
	pos += 2
	end := pos + extensionsLength
	if end > recordEnd {
		end = recordEnd
	}
	for pos+4 <= end {
		typeID := binary.BigEndian.Uint16(payload[pos : pos+2])
		size := int(binary.BigEndian.Uint16(payload[pos+2 : pos+4]))
		pos += 4
		if pos+size > end {
			return "", false
		}
		if typeID == 0 && size >= 5 {
			nameLength := int(binary.BigEndian.Uint16(payload[pos+3 : pos+5]))
			if pos+5+nameLength <= pos+size {
				return strings.ToLower(string(payload[pos+5 : pos+5+nameLength])), true
			}
		}
		pos += size
	}
	return "", false
}
