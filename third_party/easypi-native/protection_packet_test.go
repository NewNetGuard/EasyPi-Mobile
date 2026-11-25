package easypinative

import (
	"encoding/binary"
	"net/netip"
	"testing"
)

func TestDirectTCPPacketCodecIPv4(t *testing.T) {
	flow := prismFlowKey{
		source: netip.MustParseAddr("10.111.0.1"), sourcePort: 45123,
		destination: netip.MustParseAddr("203.0.113.8"), destPort: 443, protocol: 6,
	}
	packet, err := buildDirectTCPPacket(flow, 4, 100, 200, directTCPFlagACK|directTCPFlagPSH, []byte("hello"), 17)
	if err != nil {
		t.Fatal(err)
	}
	view, err := parseDirectPacket(packet)
	if err != nil {
		t.Fatal(err)
	}
	if view.flow.source != flow.destination || view.flow.destination != flow.source ||
		view.flow.sourcePort != flow.destPort || view.flow.destPort != flow.sourcePort {
		t.Fatalf("reply tuple was not reversed: %#v", view.flow)
	}
	if view.sequence != 100 || view.acknowledgement != 200 || string(view.payload) != "hello" {
		t.Fatalf("unexpected TCP packet: %#v payload=%q", view, view.payload)
	}
	if internetChecksum(packet[:20], 0) != 0 {
		t.Fatal("invalid IPv4 header checksum")
	}
	initial := checksumSum(packet[12:20], 0) + 6 + uint32(len(packet)-20)
	if internetChecksum(packet[20:], initial) != 0 {
		t.Fatal("invalid TCP checksum")
	}
}

func TestDirectUDPReplyCodecIPv6(t *testing.T) {
	flow := prismFlowKey{
		source: netip.MustParseAddr("fd00::1"), sourcePort: 53000,
		destination: netip.MustParseAddr("2001:db8::53"), destPort: 53, protocol: 17,
	}
	packet, err := buildDirectUDPReply(flow, 6, []byte{1, 2, 3, 4}, 0)
	if err != nil {
		t.Fatal(err)
	}
	view, err := parseDirectPacket(packet)
	if err != nil {
		t.Fatal(err)
	}
	if view.flow.source != flow.destination || view.flow.destination != flow.source || string(view.payload) != "\x01\x02\x03\x04" {
		t.Fatalf("unexpected UDP reply: %#v payload=%v", view, view.payload)
	}
	initial := checksumSum(packet[8:40], 0) + uint32(len(packet)-40) + 17
	if internetChecksum(packet[40:], initial) != 0 {
		t.Fatal("invalid IPv6 UDP checksum")
	}
}

func TestDirectPacketRejectsInvalidHeaders(t *testing.T) {
	for _, packet := range [][]byte{{}, {0x45}, make([]byte, 19), append([]byte{0x60}, make([]byte, 38)...)} {
		if _, err := parseDirectPacket(packet); err == nil {
			t.Fatalf("accepted malformed packet %x", packet)
		}
	}
}

func TestDirectSequenceComparisonWraps(t *testing.T) {
	if !directSequenceAfter(2, ^uint32(0)-2) || !directSequenceBefore(^uint32(0)-2, 2) {
		t.Fatal("TCP sequence comparison did not handle wraparound")
	}
	packet := testIPv4Packet([4]byte{10, 111, 0, 1}, [4]byte{203, 0, 113, 8}, 40000, 443, 6)
	packet = append(packet, make([]byte, 20)...)
	packet[32] = 0x50
	binary.BigEndian.PutUint32(packet[24:28], 123)
	if view, err := parseDirectPacket(packet); err != nil || view.sequence != 123 {
		t.Fatalf("failed to parse valid TCP packet: %#v %v", view, err)
	}
}
