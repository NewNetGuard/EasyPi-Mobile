package easypinative

import (
	"encoding/binary"
	"errors"
	"testing"
	"time"
)

func TestDNSProxyReturnsPolicyBlockAndReportsRealEvent(t *testing.T) {
	clearPrismBehaviorState()
	observer := &testBlockObserver{}
	SetPrismBlockObserver(observer)
	defer ClearPrismBlockObserver()
	SetPrismDomainPolicyResolver(testDomainPolicy{"blocked.example": true})
	defer ClearPrismDomainPolicyResolver()

	request := dnsQueryPacket("blocked.example", 53100)
	injected := make(chan []byte, 1)
	if !prismInterceptDNS(request, func(packet []byte) error {
		injected <- append([]byte(nil), packet...)
		return nil
	}, time.Now()) {
		t.Fatal("DNS packet was not intercepted")
	}

	select {
	case response := <-injected:
		if len(response) < 40 || binary.BigEndian.Uint16(response[20:22]) != 53 ||
			binary.BigEndian.Uint16(response[22:24]) != 53100 {
			t.Fatalf("invalid UDP response tuple: %x", response)
		}
		flags := binary.BigEndian.Uint16(response[30:32])
		if flags&0x8000 == 0 || flags&0x000f != 3 {
			t.Fatalf("expected an NXDOMAIN response, flags=%04x", flags)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("DNS proxy did not inject a response")
	}

	if len(observer.queries) != 1 || observer.queries[0] != "blocked.example" {
		t.Fatalf("query was not reported: %#v", observer.queries)
	}
	if len(observer.events) != 1 || observer.events[0].domain != "blocked.example" {
		t.Fatalf("block was not reported: %#v", observer.events)
	}
}

func TestDNSStatisticsCountRequestsRatherThanDeduplicatedBlocks(t *testing.T) {
	clearPrismBehaviorState()
	observer := &testBlockObserver{}
	SetPrismBlockObserver(observer)
	defer ClearPrismBlockObserver()
	SetPrismDomainPolicyResolver(testDomainPolicy{"blocked.example": true})
	defer ClearPrismDomainPolicyResolver()
	now := time.Now()
	for i := 0; i < 2; i++ {
		done := make(chan struct{})
		prismInterceptDNSVia(dnsQueryPacket("blocked.example", 53120), func([]byte) error {
			close(done)
			return nil
		}, now, func([]byte) ([]byte, error) {
			t.Error("blocked query reached upstream")
			return nil, nil
		})
		select {
		case <-done:
		case <-time.After(2 * time.Second):
			t.Fatal("DNS outcome not reported")
		}
	}
	if len(observer.dnsResults) != 2 || len(observer.events) != 1 {
		t.Fatalf("DNS requests must not use deduplicated block counts: DNS=%d blocks=%d", len(observer.dnsResults), len(observer.events))
	}
	for _, result := range observer.dnsResults {
		if !result.blocked || result.domain != "blocked.example" || result.occurredAtMillis != now.UnixMilli() {
			t.Fatalf("incorrect DNS outcome: %+v", result)
		}
	}
	prismReportBlocked(prismFlowKey{}, "tls.example", "policy", now)
	if len(observer.dnsResults) != 2 {
		t.Fatal("TLS block changed DNS statistics")
	}
}

func TestDNSUpstreamFailureIsNotReportedAsPolicyBlock(t *testing.T) {
	observer := &testBlockObserver{}
	SetPrismBlockObserver(observer)
	defer ClearPrismBlockObserver()
	done := make(chan struct{})
	prismInterceptDNSVia(dnsQueryPacket("allowed.example", 53121), func([]byte) error {
		close(done)
		return nil
	}, time.Now(), func([]byte) ([]byte, error) {
		return nil, errors.New("upstream unavailable")
	}, false)
	select {
	case <-done:
	case <-time.After(2 * time.Second):
		t.Fatal("DNS outcome not reported")
	}
	if len(observer.dnsResults) != 1 || observer.dnsResults[0].blocked {
		t.Fatalf("upstream failure incorrectly counted as a policy block: %+v", observer.dnsResults)
	}
}
