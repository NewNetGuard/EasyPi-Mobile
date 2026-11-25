package easypinative

import (
	"encoding/binary"
	"sort"
	"strings"
	"sync"
	"time"
)

const maxTLSHelloBytes = 16384
const maxTLSFlows = 1024
const maxPendingTLSFlows = 128
const maxHeldTLSPackets = 64
const maxHeldTLSBytes = 64 * 1024
const prismTLSPruneInterval = 5 * time.Second

type prismTLSFlow struct {
	base      uint32
	lastSeq   uint32
	advanced  uint64
	data      []byte
	seen      []bool
	end       int
	host      string
	decided   bool
	blocked   bool
	poisoned  bool
	checking  bool
	touched   time.Time
	checked   time.Time
	held      map[uint32][]byte
	heldBytes int
	release   func([]byte)
}

var prismTLS = struct {
	sync.RWMutex
	flows  map[prismFlowKey]*prismTLSFlow
	pruned time.Time
}{flows: make(map[prismFlowKey]*prismTLSFlow)}

var prismTLSLookups = make(chan struct{}, 8)

func clearPrismTLS() { prismTLS.Lock(); clear(prismTLS.flows); prismTLS.Unlock() }

// Segments are held from the forwarder until the complete handshake has an
// authoritative decision. TCP retransmission delivers earlier held bytes after
// an allow decision; no fragment is granted access on an incomplete SNI.
func prismTLSPolicyDrop(packet []byte, flow prismFlowKey, payload []byte, now time.Time) bool {
	return prismTLSPolicyDropWithRelease(packet, flow, payload, now, nil)
}

// prismTLSPolicyDropWithRelease holds an unverified ClientHello exactly as the
// fail-closed gate always has, but can release the already-checked packets as
// soon as EasyPi returns an allow decision. This avoids waiting for the TCP
// sender's retransmission timer without granting an unknown domain access.
func prismTLSPolicyDropWithRelease(packet []byte, flow prismFlowKey, payload []byte, now time.Time, release func([]byte)) bool {
	if flow.protocol != 6 || flow.destPort != 443 {
		return false
	}
	offset := 40
	if packet[0]>>4 == 4 {
		offset = int(packet[0]&15) * 4
	}
	if len(packet) < offset+20 {
		return true
	}
	seq := binary.BigEndian.Uint32(packet[offset+4 : offset+8])
	flags := packet[offset+13]
	// A payload-free FIN or RST is the transport's terminal metadata for this
	// tuple. Forget its TLS decision immediately instead of retaining every
	// completed HTTPS connection until the five-minute pruning pass. Keeping
	// those entries exhausted maxTLSFlows during bursts of short connections
	// and made new HTTPS handshakes fail closed even though the old flows had
	// already ended.
	if len(payload) == 0 && flags&(1|4) != 0 {
		prismTLS.Lock()
		delete(prismTLS.flows, flow)
		prismTLS.Unlock()
		return false
	}
	// Pure ACKs carry no destination metadata and cannot alter a previous
	// ClientHello decision. Avoid serialising the high-rate ACK path while a new
	// SYN still takes the exclusive tuple-reset path below.
	if len(payload) == 0 && flags&(2|4) == 0 {
		return false
	}
	// The common upload path is application data on an already-authorised TLS
	// flow. It is safe to read that state concurrently when the packet cannot
	// overlap the immutable ClientHello prefix and the authoritative decision
	// is still fresh. The exclusive path remains mandatory for retransmitted
	// handshake bytes, policy refresh, tuple reuse, blocked/poisoned flows and
	// cleanup. Consequently this removes contention without creating an allow
	// path for unknown or mutable destination metadata.
	if flags&(2|4) == 0 && len(payload) > 0 {
		prismTLS.RLock()
		state := prismTLS.flows[flow]
		fastAllowed := false
		if state != nil && state.decided && !state.blocked && !state.poisoned && !state.checking &&
			now.Sub(state.checked) < 2*time.Second {
			relative := int64(int32(seq - state.base))
			overlapsHeldPrefix := len(state.data) > 0 && relative < int64(len(state.data)) &&
				relative+int64(len(payload)) > 0
			fastAllowed = !overlapsHeldPrefix
		}
		prismTLS.RUnlock()
		if fastAllowed {
			return false
		}
	}
	prismTLS.Lock()
	if now.Sub(prismTLS.pruned) >= prismTLSPruneInterval {
		for key, state := range prismTLS.flows {
			ttl := 5 * time.Minute
			if !state.decided {
				ttl = 15 * time.Second
			}
			if now.Sub(state.touched) > ttl {
				delete(prismTLS.flows, key)
			}
		}
		prismTLS.pruned = now
	}
	state := prismTLS.flows[flow]
	if flags&2 != 0 { // New SYN, including tuple reuse, invalidates old grants.
		state = nil
		delete(prismTLS.flows, flow)
		seq++
	}
	if flags&4 != 0 {
		delete(prismTLS.flows, flow)
		prismTLS.Unlock()
		return false
	}
	if state == nil {
		if len(prismTLS.flows) >= maxTLSFlows {
			prismTLS.Unlock()
			return true
		}
		pending := 0
		for _, current := range prismTLS.flows {
			if !current.decided {
				pending++
			}
		}
		if pending >= maxPendingTLSFlows {
			prismTLS.Unlock()
			return len(payload) > 0
		}
		state = &prismTLSFlow{base: seq, lastSeq: seq, touched: now}
		prismTLS.flows[flow] = state
	}
	state.touched = now
	if delta := int32(seq - state.lastSeq); delta > 0 {
		state.advanced += uint64(delta)
		state.lastSeq = seq
	}
	if len(payload) == 0 {
		prismTLS.Unlock()
		return false
	}
	if state.poisoned {
		prismTLS.Unlock()
		return true
	}
	if state.checking {
		prismTLS.Unlock()
		return true
	}
	if state.decided && len(state.data) > 0 {
		// Keep held handshake bytes immutable across retransmission. Without
		// this check an app could obtain an allow for A and retransmit B at
		// the same TCP sequence before the remote peer received the prefix.
		// After half a sequence space the old prefix cannot remain in a TCP
		// receive window; release it before the next sequence-number wrap.
		if state.advanced >= 1<<31 {
			state.data = nil
		} else {
			relative := int64(int32(seq - state.base))
			start := max(int64(0), -relative)
			end := min(int64(len(payload)), int64(len(state.data))-relative)
			for i := start; i < end; i++ {
				pos := relative + i
				if state.data[pos] != payload[i] {
					state.blocked = true
					state.poisoned = true
					state.data = nil
					prismTLS.Unlock()
					return true
				}
			}
		}
	}
	if state.decided && now.Sub(state.checked) < 2*time.Second {
		blocked := state.blocked
		prismTLS.Unlock()
		return blocked
	}
	host := state.host
	if !state.decided {
		relative := int64(int32(seq - state.base))
		if relative < 0 || relative+int64(len(payload)) > maxTLSHelloBytes {
			state.decided, state.blocked = true, true
			state.poisoned = true
			state.data, state.seen, state.held = nil, nil, nil
			state.heldBytes, state.release = 0, nil
			prismTLS.Unlock()
			return true
		}
		end := int(relative) + len(payload)
		if end > len(state.data) {
			state.data = append(state.data, make([]byte, end-len(state.data))...)
			state.seen = append(state.seen, make([]bool, end-len(state.seen))...)
		}
		for i, value := range payload {
			pos := int(relative) + i
			if state.seen[pos] && state.data[pos] != value {
				state.decided, state.blocked = true, true
				state.poisoned = true
				state.data, state.seen, state.held = nil, nil, nil
				state.heldBytes, state.release = 0, nil
				prismTLS.Unlock()
				return true
			}
			state.data[pos], state.seen[pos] = value, true
		}
		if release != nil {
			if state.release == nil {
				state.release = release
			}
			if state.held == nil {
				state.held = make(map[uint32][]byte)
			}
			previous := state.held[seq]
			if previous == nil || len(packet) > len(previous) {
				nextBytes := state.heldBytes - len(previous) + len(packet)
				if (previous == nil && len(state.held) >= maxHeldTLSPackets) || nextBytes > maxHeldTLSBytes {
					state.decided, state.blocked = true, true
					state.poisoned = true
					state.data, state.seen, state.held = nil, nil, nil
					state.heldBytes, state.release = 0, nil
					prismTLS.Unlock()
					return true
				}
				state.held[seq] = append([]byte(nil), packet...)
				state.heldBytes = nextBytes
			}
		}
		for state.end < len(state.seen) && state.seen[state.end] {
			state.end++
		}
		var complete bool
		var reason string
		host, complete, reason = completeTLSHelloDomainReason(state.data[:state.end])
		if !complete {
			prismTLS.Unlock()
			return true
		}
		if host == "" {
			state.data, state.seen, state.held = nil, nil, nil
			state.heldBytes, state.release = 0, nil
			state.decided, state.blocked = true, true
			state.poisoned = true
			prismTLS.Unlock()
			prismReportBlocked(flow, "", reason, now)
			return true
		}
	}
	state.checking = true
	prismTLS.Unlock()
	prismDomainPolicy.RLock()
	resolver := prismDomainPolicy.resolver
	prismDomainPolicy.RUnlock()
	if resolver != nil && !resolver.ShouldBlock(host) {
		released := finishPrismTLSDecision(flow, state, host, false, now)
		return released
	}
	select {
	case prismTLSLookups <- struct{}{}:
		go func() {
			defer func() { <-prismTLSLookups }()
			blocked := resolver == nil || resolver.CheckBlock(host)
			finishPrismTLSDecision(flow, state, host, blocked, time.Now())
		}()
	default:
		prismTLS.Lock()
		if prismTLS.flows[flow] == state {
			state.checking = false
		}
		prismTLS.Unlock()
	}
	return true
}

func finishPrismTLSDecision(flow prismFlowKey, state *prismTLSFlow, host string, blocked bool, now time.Time) bool {
	var release func([]byte)
	var held []struct {
		sequence uint32
		packet   []byte
	}
	prismTLS.Lock()
	if prismTLS.flows[flow] == state {
		state.host, state.decided, state.blocked, state.checked, state.checking = host, true, blocked, now, false
		if state.seen != nil {
			state.data = state.data[:state.end]
		}
		state.seen = nil
		if !blocked && state.release != nil && len(state.held) > 0 {
			release = state.release
			held = make([]struct {
				sequence uint32
				packet   []byte
			}, 0, len(state.held))
			for sequence, packet := range state.held {
				held = append(held, struct {
					sequence uint32
					packet   []byte
				}{sequence: sequence, packet: packet})
			}
			sort.Slice(held, func(i, j int) bool {
				return int32(held[i].sequence-state.base) < int32(held[j].sequence-state.base)
			})
		}
		state.held = nil
		state.heldBytes = 0
		state.release = nil
	} else {
		prismTLS.Unlock()
		return false
	}
	if blocked {
		prismReportBlocked(flow, host, "policy", now)
	}
	prismTLS.Unlock()
	for _, item := range held {
		release(item.packet)
	}
	return len(held) > 0
}

func completeTLSHelloDomain(stream []byte) (string, bool) {
	host, complete, _ := completeTLSHelloDomainReason(stream)
	return host, complete
}

func completeTLSHelloDomainReason(stream []byte) (string, bool, string) {
	var hello []byte
	for len(stream) >= 5 {
		size := int(binary.BigEndian.Uint16(stream[3:5]))
		if stream[0] != 22 || size == 0 || size > maxTLSHelloBytes {
			return "", true, "tls_invalid_client_hello"
		}
		if len(stream) < 5+size {
			return "", false, ""
		}
		hello = append(hello, stream[5:5+size]...)
		stream = stream[5+size:]
		if len(hello) < 4 {
			continue
		}
		length := int(hello[1])<<16 | int(hello[2])<<8 | int(hello[3])
		if hello[0] != 1 || length > maxTLSHelloBytes-4 {
			return "", true, "tls_invalid_client_hello"
		}
		if len(hello) < length+4 {
			continue
		}
		hello = hello[:length+4]
		pos := 4 + 2 + 32
		if pos >= len(hello) {
			return "", true, "tls_invalid_client_hello"
		}
		pos += 1 + int(hello[pos])
		if pos+2 > len(hello) {
			return "", true, "tls_invalid_client_hello"
		}
		pos += 2 + int(binary.BigEndian.Uint16(hello[pos:pos+2]))
		if pos >= len(hello) {
			return "", true, "tls_invalid_client_hello"
		}
		pos += 1 + int(hello[pos])
		if pos+2 > len(hello) {
			return "", true, "tls_invalid_client_hello"
		}
		end := pos + 2 + int(binary.BigEndian.Uint16(hello[pos:pos+2]))
		pos += 2
		if end != len(hello) {
			return "", true, "tls_invalid_client_hello"
		}
		host := ""
		hasECH := false
		for pos+4 <= end {
			kind := binary.BigEndian.Uint16(hello[pos : pos+2])
			size := int(binary.BigEndian.Uint16(hello[pos+2 : pos+4]))
			pos += 4
			if pos+size > end {
				return "", true, "tls_invalid_client_hello"
			}
			if kind == 0xfe0d {
				// Chrome may send either real ECH or GREASE together with a
				// syntactically valid outer SNI. The local observer cannot tell
				// those cases apart, so retain the diagnostic but continue parsing
				// the visible name. Treating the mere presence of 0xfe0d as a
				// malformed ClientHello would black-hole ordinary HTTPS.
				hasECH = true
			}
			if kind == 0 {
				if host != "" || size < 5 || hello[pos+2] != 0 {
					return "", true, "tls_invalid_client_hello"
				}
				n := int(binary.BigEndian.Uint16(hello[pos+3 : pos+5]))
				if n < 1 || n > 253 || 5+n != size {
					return "", true, "tls_invalid_client_hello"
				}
				host = strings.ToLower(string(hello[pos+5 : pos+5+n]))
				for _, c := range host {
					if !(c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '-' || c == '.') {
						return "", true, "tls_invalid_client_hello"
					}
				}
			}
			pos += size
		}
		if pos != end {
			return "", true, "tls_invalid_client_hello"
		}
		if hasECH {
			return host, true, "tls_ech_extension"
		}
		return host, true, ""
	}
	return "", false, ""
}
