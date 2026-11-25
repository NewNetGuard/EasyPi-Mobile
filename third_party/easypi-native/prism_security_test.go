package easypinative

import (
	"bytes"
	"encoding/binary"
	"sync/atomic"
	"testing"
	"time"
)

func TestRetransmissionCannotReplaceAnApprovedHeldHostname(t *testing.T) {
	clearPrismBehaviorState()
	SetPrismDomainPolicyResolver(&authoritativePolicy{})
	defer ClearPrismDomainPolicyResolver()
	allowed := tlsPacket("allowed.example", 48003, [4]byte{192, 0, 2, 1})
	blocked := tlsPacket("blocked.example", 48003, [4]byte{192, 0, 2, 1})
	cut := bytes.Index(allowed, []byte("allowed.example")) + 3
	first := append([]byte(nil), allowed[:cut]...)
	second := append(append([]byte(nil), allowed[:40]...), allowed[cut:]...)
	binary.BigEndian.PutUint32(second[24:28], uint32(cut-40))
	if !prismBehaviorShouldDrop(first, time.Now()) || !prismBehaviorShouldDrop(second, time.Now()) {
		t.Fatal("unchecked handshake escaped")
	}
	waitTLSVerdict(t, second)
	if !prismBehaviorShouldDrop(blocked[:cut], time.Now()) {
		t.Fatal("approved prefix replaced by blocked hostname")
	}
}

type authoritativePolicy struct{ calls atomic.Int32 }

func (p *authoritativePolicy) ShouldBlock(string) bool { return true }
func (p *authoritativePolicy) CheckBlock(domain string) bool {
	p.calls.Add(1)
	return domain == "blocked.example"
}

func TestTLSUsesAuthoritativePolicyBeforeFirstPayload(t *testing.T) {
	clearPrismBehaviorState()
	policy := &authoritativePolicy{}
	SetPrismDomainPolicyResolver(policy)
	defer ClearPrismDomainPolicyResolver()
	packet := tlsPacket("blocked.example", 48000, [4]byte{192, 0, 2, 1})
	if !prismBehaviorShouldDrop(packet, time.Now()) {
		t.Fatal("uncached blocked handshake escaped")
	}
	waitTLSVerdict(t, packet)
	if policy.calls.Load() != 1 {
		t.Fatal("authoritative decision not consulted")
	}
	record := append(append([]byte(nil), packet[:40]...), []byte{23, 3, 3, 0, 1, 0}...)
	if !prismBehaviorShouldDrop(record, time.Now()) {
		t.Fatal("blocked flow continued with encrypted records")
	}
}

func TestTLSFlowIsReleasedByTerminalPacket(t *testing.T) {
	clearPrismBehaviorState()
	SetPrismDomainPolicyResolver(&authoritativePolicy{})
	defer ClearPrismDomainPolicyResolver()

	hello := tlsPacket("allowed.example", 48010, [4]byte{192, 0, 2, 10})
	if !prismBehaviorShouldDrop(hello, time.Now()) {
		t.Fatal("unverified ClientHello escaped")
	}
	waitTLSVerdict(t, hello)
	flow, ok := parsePrismFlow(hello)
	if !ok {
		t.Fatal("could not parse TLS test flow")
	}
	prismTLS.RLock()
	_, presentBeforeFIN := prismTLS.flows[flow]
	prismTLS.RUnlock()
	if !presentBeforeFIN {
		t.Fatal("authorised TLS flow was not retained while active")
	}

	fin := append([]byte(nil), hello[:40]...)
	fin[33] = 0x11 // FIN + ACK
	if prismBehaviorShouldDrop(fin, time.Now()) {
		t.Fatal("terminal TCP packet was blocked")
	}
	prismTLS.RLock()
	_, presentAfterFIN := prismTLS.flows[flow]
	prismTLS.RUnlock()
	if presentAfterFIN {
		t.Fatal("completed TLS flow remained in the bounded state table")
	}
}

func TestAllowedTLSFastPathStillRefreshesAuthoritativePolicy(t *testing.T) {
	clearPrismBehaviorState()
	policy := &authoritativePolicy{}
	SetPrismDomainPolicyResolver(policy)
	defer ClearPrismDomainPolicyResolver()
	now := time.Now()
	hello := tlsPacket("allowed.example", 48009, [4]byte{192, 0, 2, 9})
	if !prismBehaviorShouldDrop(hello, now) {
		t.Fatal("unverified ClientHello escaped")
	}
	waitTLSVerdict(t, hello)
	applicationData := append(append([]byte(nil), hello[:40]...), []byte{23, 3, 3, 0, 1, 0}...)
	binary.BigEndian.PutUint32(applicationData[24:28], uint32(len(hello)-40))
	if prismBehaviorShouldDrop(applicationData, now.Add(time.Second)) {
		t.Fatal("fresh allowed flow was blocked")
	}
	if !prismBehaviorShouldDrop(applicationData, now.Add(3*time.Second)) {
		t.Fatal("fast path skipped the mandatory policy refresh")
	}
}

func TestAllowedTLSIsReleasedImmediatelyAfterAuthoritativeDecision(t *testing.T) {
	clearPrismBehaviorState()
	SetPrismDomainPolicyResolver(&authoritativePolicy{})
	defer ClearPrismDomainPolicyResolver()
	packet := tlsPacket("allowed.example", 48006, [4]byte{192, 0, 2, 6})
	released := make(chan []byte, 1)
	if !prismBehaviorShouldDropWithRelease(packet, time.Now(), func(held []byte) {
		released <- append([]byte(nil), held...)
	}) {
		t.Fatal("unverified ClientHello was not held")
	}
	waitTLSVerdict(t, packet)
	select {
	case got := <-released:
		if !bytes.Equal(got, packet) {
			t.Fatal("released packet differs from the authenticated held packet")
		}
	case <-time.After(time.Second):
		t.Fatal("allowed ClientHello waited for TCP retransmission")
	}
}

func TestBlockedTLSIsNeverReleased(t *testing.T) {
	clearPrismBehaviorState()
	SetPrismDomainPolicyResolver(&authoritativePolicy{})
	defer ClearPrismDomainPolicyResolver()
	packet := tlsPacket("blocked.example", 48007, [4]byte{192, 0, 2, 7})
	released := make(chan []byte, 1)
	if !prismBehaviorShouldDropWithRelease(packet, time.Now(), func(held []byte) {
		released <- append([]byte(nil), held...)
	}) {
		t.Fatal("blocked ClientHello escaped before its decision")
	}
	waitTLSVerdict(t, packet)
	select {
	case <-released:
		t.Fatal("blocked ClientHello was released")
	case <-time.After(25 * time.Millisecond):
	}
}

func TestSplitAllowedTLSIsReleasedInSequenceOrder(t *testing.T) {
	clearPrismBehaviorState()
	SetPrismDomainPolicyResolver(&authoritativePolicy{})
	defer ClearPrismDomainPolicyResolver()
	packet := tlsPacket("allowed.example", 48008, [4]byte{192, 0, 2, 8})
	const cut = 60
	first := append([]byte(nil), packet[:cut]...)
	second := append(append([]byte(nil), packet[:40]...), packet[cut:]...)
	binary.BigEndian.PutUint32(second[24:28], uint32(cut-40))
	released := make(chan []byte, 2)
	release := func(held []byte) { released <- append([]byte(nil), held...) }
	if !prismBehaviorShouldDropWithRelease(first, time.Now(), release) ||
		!prismBehaviorShouldDropWithRelease(second, time.Now(), release) {
		t.Fatal("split unverified ClientHello escaped")
	}
	waitTLSVerdict(t, second)
	select {
	case got := <-released:
		if !bytes.Equal(got, first) {
			t.Fatal("first released TLS segment was out of order")
		}
	case <-time.After(time.Second):
		t.Fatal("first TLS segment was not released")
	}
	select {
	case got := <-released:
		if !bytes.Equal(got, second) {
			t.Fatal("second released TLS segment was out of order")
		}
	case <-time.After(time.Second):
		t.Fatal("second TLS segment was not released")
	}
}

func TestSplitClientHelloIsHeldUntilFullDomainDecision(t *testing.T) {
	for _, host := range []string{"blocked.example", "allowed.example"} {
		t.Run(host, func(t *testing.T) {
			clearPrismBehaviorState()
			SetPrismDomainPolicyResolver(&authoritativePolicy{})
			defer ClearPrismDomainPolicyResolver()
			packet := tlsPacket(host, 48001, [4]byte{192, 0, 2, 1})
			const cut = 60
			first := append([]byte(nil), packet[:cut]...)
			second := append(append([]byte(nil), packet[:40]...), packet[cut:]...)
			binary.BigEndian.PutUint32(second[24:28], uint32(cut-40))
			if !prismBehaviorShouldDrop(first, time.Now()) {
				t.Fatal("partial SNI escaped")
			}
			if !prismBehaviorShouldDrop(second, time.Now()) {
				t.Fatal("unknown decision escaped")
			}
			waitTLSVerdict(t, second)
			blocked := host == "blocked.example"
			if got := prismBehaviorShouldDrop(first, time.Now()); got != blocked {
				t.Fatal("retransmit bypassed or lost final verdict")
			}
		})
	}
}

func waitTLSVerdict(t *testing.T, packet []byte) {
	t.Helper()
	flow, ok := parsePrismFlow(packet)
	if !ok {
		t.Fatal("invalid test flow")
	}
	until := time.Now().Add(2 * time.Second)
	for time.Now().Before(until) {
		prismTLS.Lock()
		state := prismTLS.flows[flow]
		done := state != nil && state.decided && !state.checking
		prismTLS.Unlock()
		if done {
			return
		}
		time.Sleep(time.Millisecond)
	}
	t.Fatal("TLS policy did not complete")
}

type slowTLSPolicy struct {
	entered chan struct{}
	release chan struct{}
}

func (p *slowTLSPolicy) ShouldBlock(host string) bool { return host == "slow.example" }
func (p *slowTLSPolicy) CheckBlock(string) bool       { close(p.entered); <-p.release; return false }

func TestSlowPolicyDoesNotBlockTheSharedPacketPump(t *testing.T) {
	clearPrismBehaviorState()
	policy := &slowTLSPolicy{make(chan struct{}), make(chan struct{})}
	SetPrismDomainPolicyResolver(policy)
	defer ClearPrismDomainPolicyResolver()
	slow := tlsPacket("slow.example", 48004, [4]byte{192, 0, 2, 1})
	done := make(chan bool, 1)
	go func() { done <- prismBehaviorShouldDrop(slow, time.Now()) }()
	select {
	case held := <-done:
		if !held {
			t.Fatal("unknown flow escaped")
		}
	case <-time.After(time.Second):
		close(policy.release)
		t.Fatal("packet pump blocked by network policy")
	}
	select {
	case <-policy.entered:
	case <-time.After(time.Second):
		close(policy.release)
		t.Fatal("background check did not start")
	}
	fast := tlsPacket("known.example", 48005, [4]byte{192, 0, 2, 2})
	if prismBehaviorShouldDrop(fast, time.Now()) {
		close(policy.release)
		t.Fatal("unrelated verified flow was blocked")
	}
	close(policy.release)
	waitTLSVerdict(t, slow)
	if prismBehaviorShouldDrop(slow, time.Now()) {
		t.Fatal("verified held flow did not resume")
	}
}

func TestMissingPolicyAndOpaqueDNSPathsFailClosed(t *testing.T) {
	clearPrismBehaviorState()
	ClearPrismDomainPolicyResolver()
	if !prismBehaviorShouldDrop(tlsPacket("allowed.example", 48002, [4]byte{192, 0, 2, 1}), time.Now()) {
		t.Fatal("missing policy allowed TLS")
	}
	SetPrismDNSProxyEnabled(true)
	defer SetPrismDNSProxyEnabled(false)
	for _, tuple := range []struct {
		port     uint16
		protocol byte
	}{{53, 6}, {853, 6}, {853, 17}, {443, 17}} {
		p := testIPv4Packet([4]byte{10, 111, 0, 1}, [4]byte{192, 0, 2, 1}, 50000, tuple.port, tuple.protocol)
		if !prismShouldDropPrivateDNS(p) {
			t.Fatalf("opaque resolver path passed: %v", tuple)
		}
	}
}

func TestDNSConcurrencyIsBoundedAndTunnelResolverHasNoFallback(t *testing.T) {
	SetPrismDomainPolicyResolver(testDomainPolicy{})
	defer ClearPrismDomainPolicyResolver()
	entered := make(chan struct{}, 64)
	release := make(chan struct{})
	resolve := func([]byte) ([]byte, error) { entered <- struct{}{}; <-release; return make([]byte, 12), nil }
	injected := make(chan []byte, 64)
	now := time.Now()
	for i := 0; i < 64; i++ {
		prismInterceptDNSVia(dnsQueryPacket("allowed.example", uint16(50000+i)), func(p []byte) error { injected <- p; return nil }, now, resolve)
	}
	deadline := time.After(2 * time.Second)
	for i := 0; i < maxPrismDNSWorkers; i++ {
		select {
		case <-entered:
		case <-deadline:
			close(release)
			t.Fatal("worker did not start")
		}
	}
	select {
	case <-entered:
		close(release)
		t.Fatal("worker budget exceeded")
	default:
	}
	close(release)
	for i := 0; i < 64; i++ {
		select {
		case <-injected:
		case <-time.After(2 * time.Second):
			t.Fatal("query not answered or refused")
		}
	}
}
