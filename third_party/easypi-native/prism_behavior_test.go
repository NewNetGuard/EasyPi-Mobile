package easypinative

import (
	"encoding/binary"
	"testing"
	"time"
)

type testBlockEvent struct {
	domain string
	reason string
}

type testBlockObserver struct {
	queries    []string
	events     []testBlockEvent
	dnsResults []testDNSResult
}

type testDNSResult struct {
	domain           string
	blocked          bool
	occurredAtMillis int64
}

func (observer *testBlockObserver) OnDNSResult(domain string, blocked bool, occurredAtMillis int64) {
	observer.dnsResults = append(observer.dnsResults, testDNSResult{domain, blocked, occurredAtMillis})
}

func (observer *testBlockObserver) OnQuery(domain string) {
	observer.queries = append(observer.queries, domain)
}

func (observer *testBlockObserver) OnBlocked(domain, reason string) {
	observer.events = append(observer.events, testBlockEvent{domain: domain, reason: reason})
}

func dnsQueryPacket(domain string, sourcePort uint16) []byte {
	dns := make([]byte, 12)
	binary.BigEndian.PutUint16(dns[4:6], 1)
	for _, label := range splitLabels(domain) {
		dns = append(dns, byte(len(label)))
		dns = append(dns, label...)
	}
	dns = append(dns, 0, 0, 1, 0, 1)
	packet := testIPv4Packet([4]byte{10, 111, 0, 1}, [4]byte{192, 0, 2, 230}, sourcePort, 53, 17)
	packet = append(packet, make([]byte, 4)...)
	packet = append(packet, dns...)
	return packet
}

func splitLabels(domain string) []string {
	var labels []string
	start := 0
	for i := 0; i <= len(domain); i++ {
		if i == len(domain) || domain[i] == '.' {
			labels = append(labels, domain[start:i])
			start = i + 1
		}
	}
	return labels
}

func tlsPacket(host string, sourcePort uint16, destination [4]byte) []byte {
	name := []byte(host)
	sni := make([]byte, 5+len(name))
	binary.BigEndian.PutUint16(sni[0:2], uint16(3+len(name)))
	sni[2] = 0
	binary.BigEndian.PutUint16(sni[3:5], uint16(len(name)))
	copy(sni[5:], name)
	extension := make([]byte, 4+len(sni))
	binary.BigEndian.PutUint16(extension[2:4], uint16(len(sni)))
	copy(extension[4:], sni)
	body := make([]byte, 2+32+1+2+2+1+1+2+len(extension))
	pos := 2 + 32
	body[pos] = 0
	pos++
	binary.BigEndian.PutUint16(body[pos:pos+2], 2)
	pos += 2
	body[pos], body[pos+1] = 0x13, 0x01
	pos += 2
	body[pos], body[pos+1] = 1, 0
	pos += 2
	binary.BigEndian.PutUint16(body[pos:pos+2], uint16(len(extension)))
	copy(body[pos+2:], extension)
	handshake := append([]byte{1, byte(len(body) >> 16), byte(len(body) >> 8), byte(len(body))}, body...)
	record := append([]byte{22, 3, 1, byte(len(handshake) >> 8), byte(len(handshake))}, handshake...)
	packet := testIPv4Packet([4]byte{10, 111, 0, 1}, destination, sourcePort, 443, 6)
	packet = append(packet, make([]byte, 16)...)
	packet[32] = 0x50
	packet = append(packet, record...)
	return packet
}

func TestBehaviorGuardDropsCompanionTLSAfterAdSignal(t *testing.T) {
	SetPrismDomainPolicyResolver(testDomainPolicy{})
	defer ClearPrismDomainPolicyResolver()
	clearPrismBehaviorState()
	observer := &testBlockObserver{}
	SetPrismBlockObserver(observer)
	defer ClearPrismBlockObserver()
	SetPrismFlowOwnerResolver(testPrismOwner("com.android.chrome"))
	defer ClearPrismFlowOwnerResolver()
	now := time.Now()
	primary := tlsPacket("www.interfax.ru", 41000, [4]byte{1, 1, 1, 1})
	companion := tlsPacket("yandex.ru", 41001, [4]byte{2, 2, 2, 2})
	if prismBehaviorShouldDrop(primary, now) || prismBehaviorShouldDrop(companion, now.Add(time.Millisecond)) {
		t.Fatal("traffic was blocked before an advertising signal")
	}
	if prismBehaviorShouldDrop(dnsQueryPacket("ads.adfox.ru", 53000), now.Add(2*time.Millisecond)) {
		t.Fatal("the DNS signal itself must fail open")
	}
	if !prismBehaviorShouldDrop(companion, now.Add(3*time.Millisecond)) {
		t.Fatal("third-party advertising companion flow was not blocked")
	}
	if prismBehaviorShouldDrop(primary, now.Add(4*time.Millisecond)) {
		t.Fatal("first-party flow was blocked")
	}
	if len(observer.events) != 1 || observer.events[0] != (testBlockEvent{domain: "yandex.ru", reason: "behavior"}) {
		t.Fatalf("actual behavior block was not reported correctly: %#v", observer.events)
	}
}

func TestEstablishedBehaviorFlowChecksBurstOnEveryPacket(t *testing.T) {
	SetPrismDomainPolicyResolver(testDomainPolicy{})
	defer ClearPrismDomainPolicyResolver()
	clearPrismBehaviorState()
	SetPrismFlowOwnerResolver(testPrismOwner("com.android.chrome"))
	defer ClearPrismFlowOwnerResolver()
	now := time.Now()
	primary := tlsPacket("www.interfax.ru", 41010, [4]byte{1, 1, 1, 1})
	companion := tlsPacket("yandex.ru", 41011, [4]byte{2, 2, 2, 2})
	if prismBehaviorShouldDrop(primary, now) || prismBehaviorShouldDrop(companion, now.Add(time.Millisecond)) {
		t.Fatal("traffic was blocked before an advertising signal")
	}
	if prismBehaviorShouldDrop(dnsQueryPacket("ads.adfox.ru", 53010), now.Add(2*time.Millisecond)) {
		t.Fatal("the DNS signal itself must fail open")
	}
	applicationData := append(append([]byte(nil), companion[:40]...), 23, 3, 3, 0, 1, 0)
	binary.BigEndian.PutUint32(applicationData[24:28], uint32(len(companion)-40))
	if !prismBehaviorShouldDrop(applicationData, now.Add(3*time.Millisecond)) {
		t.Fatal("established companion flow bypassed the active behavior burst")
	}
}

type testPrismOwner string

func (owner testPrismOwner) Owner(_ string, _ string, _ int, _ string, _ int) string {
	return string(owner)
}

type testPortOwners map[int]string

func (owners testPortOwners) Owner(_ string, _ string, clientPort int, _ string, _ int) string {
	return owners[clientPort]
}

type testDomainPolicy map[string]bool

func (policy testDomainPolicy) ShouldBlock(domain string) bool {
	return policy[domain]
}

func (policy testDomainPolicy) CheckBlock(domain string) bool {
	return policy[domain]
}

func TestHTTPSDomainPolicyBlocksDNSAndTLSLocally(t *testing.T) {
	clearPrismBehaviorState()
	SetPrismDomainPolicyResolver(testDomainPolicy{"blocked.example": true})
	defer ClearPrismDomainPolicyResolver()
	now := time.Now()
	if !prismBehaviorShouldDrop(dnsQueryPacket("blocked.example", 53010), now) {
		t.Fatal("cached HTTPS policy did not block a DNS query")
	}
	if !prismBehaviorShouldDrop(tlsPacket("blocked.example", 46000, [4]byte{4, 4, 4, 4}), now) {
		t.Fatal("cached HTTPS policy did not block TLS SNI")
	}
	if prismBehaviorShouldDrop(dnsQueryPacket("allowed.example", 53011), now) {
		t.Fatal("HTTPS policy blocked an allowed domain")
	}
}

func TestActualNativeBlocksAreReportedAndRetransmitsAreDeduplicated(t *testing.T) {
	clearPrismBehaviorState()
	observer := &testBlockObserver{}
	SetPrismBlockObserver(observer)
	defer ClearPrismBlockObserver()
	SetPrismDomainPolicyResolver(testDomainPolicy{"blocked.example": true})
	defer ClearPrismDomainPolicyResolver()

	now := time.Now()
	packet := dnsQueryPacket("blocked.example", 53020)
	if !prismBehaviorShouldDrop(packet, now) || !prismBehaviorShouldDrop(packet, now.Add(time.Second)) {
		t.Fatal("policy did not block the test packets")
	}
	if len(observer.events) != 1 {
		t.Fatalf("expected one deduplicated block event, got %d", len(observer.events))
	}
	if observer.events[0] != (testBlockEvent{domain: "blocked.example", reason: "policy"}) {
		t.Fatalf("unexpected block event: %#v", observer.events[0])
	}
}

func TestBehaviorGuardDoesNotCrossApplicationBoundary(t *testing.T) {
	SetPrismDomainPolicyResolver(testDomainPolicy{})
	defer ClearPrismDomainPolicyResolver()
	clearPrismBehaviorState()
	SetPrismFlowOwnerResolver(testPortOwners{
		44000: "com.android.chrome",
		44001: "com.android.shell",
		53002: "com.android.chrome",
	})
	defer ClearPrismFlowOwnerResolver()
	now := time.Now()
	chrome := tlsPacket("www.interfax.ru", 44000, [4]byte{1, 1, 1, 1})
	shell := tlsPacket("example.com", 44001, [4]byte{2, 2, 2, 2})
	prismBehaviorShouldDrop(chrome, now)
	prismBehaviorShouldDrop(shell, now.Add(time.Millisecond))
	prismBehaviorShouldDrop(dnsQueryPacket("ads.adfox.ru", 53002), now.Add(2*time.Millisecond))
	if prismBehaviorShouldDrop(shell, now.Add(3*time.Millisecond)) {
		t.Fatal("Chrome advertising signal blocked another application's flow")
	}
}

func TestBehaviorGuardAllowsNavigationDuringAdBurst(t *testing.T) {
	SetPrismDomainPolicyResolver(testDomainPolicy{})
	defer ClearPrismDomainPolicyResolver()
	clearPrismBehaviorState()
	SetPrismFlowOwnerResolver(testPrismOwner("com.android.chrome"))
	defer ClearPrismFlowOwnerResolver()
	now := time.Now()
	oldPage := tlsPacket("www.interfax.ru", 45000, [4]byte{1, 1, 1, 1})
	newPage := tlsPacket("www.dw.com", 45001, [4]byte{2, 2, 2, 2})
	prismBehaviorShouldDrop(oldPage, now)
	prismBehaviorShouldDrop(dnsQueryPacket("ads.adfox.ru", 53003), now.Add(time.Millisecond))
	if prismBehaviorShouldDrop(newPage, now.Add(2*time.Millisecond)) {
		t.Fatal("a new top-level www navigation was blocked by the previous page's ad burst")
	}
}

func TestBehaviorGuardFailsOpenWithoutFlowOwner(t *testing.T) {
	SetPrismDomainPolicyResolver(testDomainPolicy{})
	defer ClearPrismDomainPolicyResolver()
	clearPrismBehaviorState()
	ClearPrismFlowOwnerResolver()
	now := time.Now()
	primary := tlsPacket("www.interfax.ru", 42000, [4]byte{1, 1, 1, 1})
	companion := tlsPacket("yandex.ru", 42001, [4]byte{2, 2, 2, 2})
	prismBehaviorShouldDrop(primary, now)
	prismBehaviorShouldDrop(companion, now.Add(time.Millisecond))
	prismBehaviorShouldDrop(dnsQueryPacket("ads.adfox.ru", 53001), now.Add(2*time.Millisecond))
	if prismBehaviorShouldDrop(companion, now.Add(3*time.Millisecond)) {
		t.Fatal("unattributed traffic must fail open")
	}
}

func TestAdvertisingHostSignalIsSemantic(t *testing.T) {
	SetPrismDomainPolicyResolver(testDomainPolicy{})
	defer ClearPrismDomainPolicyResolver()
	for _, host := range []string{"ads.example.com", "hb-bidder.example", "banner.example"} {
		if !looksLikeAdvertisingHost(host) {
			t.Fatalf("expected advertising signal for %q", host)
		}
	}
	if looksLikeAdvertisingHost("www.interfax.ru") || looksLikeAdvertisingHost("cdn.example.com") {
		t.Fatal("ordinary content host was treated as advertising")
	}
}

func TestTLSClientHelloSNIParsesFromFirstSegment(t *testing.T) {
	SetPrismDomainPolicyResolver(testDomainPolicy{})
	defer ClearPrismDomainPolicyResolver()
	packet := tlsPacket("www.interfax.ru", 43000, [4]byte{3, 3, 3, 3})
	payload := packet[40:]
	// Advertise a larger TLS record to model a ClientHello split across TCP
	// segments. The first segment still contains the complete SNI extension.
	binary.BigEndian.PutUint16(payload[3:5], uint16(len(payload)+512))
	host, ok := parseTLSClientHelloSNI(payload)
	if !ok || host != "www.interfax.ru" {
		t.Fatalf("unexpected partial ClientHello result: host=%q ok=%v", host, ok)
	}
}
