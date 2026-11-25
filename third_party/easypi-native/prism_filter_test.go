package easypinative

import (
	"encoding/binary"
	"testing"
	"time"
)

func testIPv4Packet(source, destination [4]byte, sourcePort, destinationPort uint16, protocol byte) []byte {
	packet := make([]byte, 24)
	packet[0] = 0x45
	packet[9] = protocol
	copy(packet[12:16], source[:])
	copy(packet[16:20], destination[:])
	binary.BigEndian.PutUint16(packet[20:22], sourcePort)
	binary.BigEndian.PutUint16(packet[22:24], destinationPort)
	return packet
}

func TestPrismExactFlowRule(t *testing.T) {
	ClearPrismFlowRules()
	if prismRuleCount.Load() != 0 {
		t.Fatal("cleared rule count is not zero")
	}
	if err := SetPrismFlowRule("100.64.0.2", 43120, "157.240.241.17", 443, "tcp", 5_000); err != nil {
		t.Fatal(err)
	}
	if prismRuleCount.Load() != 1 {
		t.Fatal("active rule count was not published")
	}
	match := testIPv4Packet([4]byte{100, 64, 0, 2}, [4]byte{157, 240, 241, 17}, 43120, 443, 6)
	if !prismShouldDrop(match, time.Now()) {
		t.Fatal("exact flow was not dropped")
	}
	other := testIPv4Packet([4]byte{100, 64, 0, 2}, [4]byte{157, 240, 241, 17}, 43121, 443, 6)
	if prismShouldDrop(other, time.Now()) {
		t.Fatal("different flow was dropped")
	}
	reply := testIPv4Packet([4]byte{157, 240, 241, 17}, [4]byte{100, 64, 0, 2}, 443, 43120, 6)
	if prismShouldDrop(reply, time.Now()) {
		t.Fatal("directional egress rule matched an ingress reply")
	}
}

func TestReplacingPrismRuleDoesNotInflateFastPathCount(t *testing.T) {
	ClearPrismFlowRules()
	defer ClearPrismFlowRules()
	for range 3 {
		if err := SetPrismFlowRule("100.64.0.2", 43120, "157.240.241.17", 443, "tcp", 5_000); err != nil {
			t.Fatal(err)
		}
	}
	if prismRuleCount.Load() != 1 {
		t.Fatalf("active rule count = %d, want 1", prismRuleCount.Load())
	}
}

func TestPrismRuleExpiresAndMalformedPacketsFailOpen(t *testing.T) {
	ClearPrismFlowRules()
	if err := SetPrismFlowRule("100.64.0.2", 1234, "1.1.1.1", 443, "udp", 100); err != nil {
		t.Fatal(err)
	}
	packet := testIPv4Packet([4]byte{100, 64, 0, 2}, [4]byte{1, 1, 1, 1}, 1234, 443, 17)
	if prismShouldDrop(packet, time.Now().Add(time.Second)) {
		t.Fatal("expired rule still dropped traffic")
	}
	if prismShouldDrop([]byte{0x45, 0x00}, time.Now()) {
		t.Fatal("malformed packet was dropped")
	}
}
