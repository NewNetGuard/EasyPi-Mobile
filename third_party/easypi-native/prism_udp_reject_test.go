package easypinative

import (
	"bytes"
	"encoding/binary"
	"testing"
	"time"
)

func TestUDPRejectIPv4QuotesOnlyHeaders(t *testing.T) {
	packet := dnsQueryPacket("private.example", 53130)
	binary.BigEndian.PutUint16(packet[22:24], 443)
	response := prismUDPUnreachable(packet)
	if len(response) != 56 || response[9] != 1 || response[20] != 3 || response[21] != 3 {
		t.Fatal("missing IPv4 port-unreachable response")
	}
	if !bytes.Equal(response[12:16], packet[16:20]) || !bytes.Equal(response[16:20], packet[12:16]) || !bytes.Equal(response[28:], packet[:28]) {
		t.Fatal("incorrect error tuple or quoted headers")
	}
	if internetChecksum(response[:20], 0) != 0 || internetChecksum(response[20:], 0) != 0 {
		t.Fatal("invalid IPv4/ICMP checksum")
	}
	if bytes.Contains(response, []byte("private")) {
		t.Fatal("application payload leaked into ICMP quote")
	}
}

func TestUninspectableTLSReportsCategoryWithoutInput(t *testing.T) {
	clearPrismBehaviorState()
	observer := &testBlockObserver{}
	SetPrismBlockObserver(observer)
	defer ClearPrismBlockObserver()
	packet := tlsPacket("private.example", 53131, [4]byte{192, 0, 2, 1})
	packet[40] = 23 // Application data cannot establish a visible ClientHello.
	flow, payload, ok := parsePrismTransport(packet)
	if !ok || !prismTLSPolicyDrop(packet, flow, payload, time.Now()) {
		t.Fatal("uninspectable TLS escaped")
	}
	if len(observer.events) != 1 || observer.events[0].domain != "" || observer.events[0].reason != "tls_invalid_client_hello" {
		t.Fatal("missing redacted diagnostic category")
	}
}

func TestECHDiagnosticPreservesValidatedOuterName(t *testing.T) {
	packet := tlsPacket("public.example", 53132, [4]byte{192, 0, 2, 1})
	if host, complete, reason := completeTLSHelloDomainReason(packet[40:]); host != "public.example" || !complete || reason != "" {
		t.Fatal("ordinary visible ClientHello was rejected")
	}
	// Append an ECH-shaped extension without interpreting its encrypted bytes.
	packet = append(packet, 0xfe, 0x0d, 0, 1, 0)
	binary.BigEndian.PutUint16(packet[43:45], uint16(len(packet)-45))
	size := len(packet) - 49
	packet[46], packet[47], packet[48] = byte(size>>16), byte(size>>8), byte(size)
	binary.BigEndian.PutUint16(packet[90:92], uint16(len(packet)-92))
	if host, complete, reason := completeTLSHelloDomainReason(packet[40:]); host != "public.example" || !complete || reason != "tls_ech_extension" {
		t.Fatal("ECH or GREASE must retain the validated outer name and diagnostic category")
	}
}

func TestUDPRejectIPv6ChecksumAndQuote(t *testing.T) {
	packet := make([]byte, 60)
	packet[0], packet[6], packet[7] = 0x60, 17, 64
	binary.BigEndian.PutUint16(packet[4:6], 20)
	packet[8], packet[23], packet[24], packet[39] = 0x20, 1, 0x20, 2
	binary.BigEndian.PutUint16(packet[40:42], 53130)
	binary.BigEndian.PutUint16(packet[42:44], 443)
	binary.BigEndian.PutUint16(packet[44:46], 20)
	copy(packet[48:], "private-data")
	response := prismUDPUnreachable(packet)
	if len(response) != 96 || response[6] != 58 || response[40] != 1 || response[41] != 4 || !bytes.Equal(response[48:], packet[:48]) {
		t.Fatal("invalid IPv6 unreachable response")
	}
	sum := checksumSum(response[8:40], 0) + uint32(len(response)-40) + 58
	if internetChecksum(response[40:], sum) != 0 {
		t.Fatal("invalid ICMPv6 checksum")
	}
	if !bytes.Equal(response[8:24], packet[24:40]) || !bytes.Equal(response[24:40], packet[8:24]) {
		t.Fatal("incorrect IPv6 error tuple")
	}
}

func TestUDPRejectDoesNotReplyToMalformedTCPOrMulticast(t *testing.T) {
	packet := dnsQueryPacket("private.example", 53130)
	for _, p := range [][]byte{nil, {0x45}, append([]byte(nil), packet[:22]...)} {
		if prismUDPUnreachable(p) != nil {
			t.Fatal("responded to malformed packet")
		}
	}
	packet[9] = 6
	if prismUDPUnreachable(packet) != nil {
		t.Fatal("responded to TCP")
	}
	packet[9], packet[16] = 17, 224
	if prismUDPUnreachable(packet) != nil {
		t.Fatal("responded to multicast")
	}
}
