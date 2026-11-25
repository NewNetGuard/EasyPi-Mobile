package easypinative

import (
	"encoding/binary"
	"errors"
	"net/netip"
)

const (
	directTCPFlagFIN = 0x01
	directTCPFlagSYN = 0x02
	directTCPFlagRST = 0x04
	directTCPFlagPSH = 0x08
	directTCPFlagACK = 0x10
)

var errUnsupportedProtectionPacket = errors.New("unsupported protection packet")

type directPacketView struct {
	version         uint8
	flow            prismFlowKey
	transportOffset int
	payloadOffset   int
	payload         []byte
	sequence        uint32
	acknowledgement uint32
	tcpFlags        uint8
	window          uint16
}

func parseDirectPacket(packet []byte) (directPacketView, error) {
	flow, ok := parsePrismFlow(packet)
	if !ok {
		return directPacketView{}, errUnsupportedProtectionPacket
	}
	view := directPacketView{version: packet[0] >> 4, flow: flow}
	switch view.version {
	case 4:
		view.transportOffset = int(packet[0]&0x0f) * 4
	case 6:
		view.transportOffset = 40
	default:
		return directPacketView{}, errUnsupportedProtectionPacket
	}
	if flow.protocol == 17 {
		if len(packet) < view.transportOffset+8 {
			return directPacketView{}, errUnsupportedProtectionPacket
		}
		view.payloadOffset = view.transportOffset + 8
		view.payload = packet[view.payloadOffset:]
		return view, nil
	}
	if flow.protocol != 6 || len(packet) < view.transportOffset+20 {
		return directPacketView{}, errUnsupportedProtectionPacket
	}
	headerLength := int(packet[view.transportOffset+12]>>4) * 4
	if headerLength < 20 || len(packet) < view.transportOffset+headerLength {
		return directPacketView{}, errUnsupportedProtectionPacket
	}
	view.payloadOffset = view.transportOffset + headerLength
	view.payload = packet[view.payloadOffset:]
	view.sequence = binary.BigEndian.Uint32(packet[view.transportOffset+4 : view.transportOffset+8])
	view.acknowledgement = binary.BigEndian.Uint32(packet[view.transportOffset+8 : view.transportOffset+12])
	view.tcpFlags = packet[view.transportOffset+13]
	view.window = binary.BigEndian.Uint16(packet[view.transportOffset+14 : view.transportOffset+16])
	return view, nil
}

func buildDirectTCPPacket(
	flow prismFlowKey,
	version uint8,
	sequence uint32,
	acknowledgement uint32,
	flags uint8,
	payload []byte,
	ipID uint16,
) ([]byte, error) {
	const tcpHeaderLength = 20
	ipHeaderLength := 20
	if version == 6 {
		ipHeaderLength = 40
	}
	packet := make([]byte, ipHeaderLength+tcpHeaderLength+len(payload))
	if err := writeDirectIPHeader(packet, flow.destination, flow.source, version, 6, ipID); err != nil {
		return nil, err
	}
	offset := ipHeaderLength
	binary.BigEndian.PutUint16(packet[offset:offset+2], flow.destPort)
	binary.BigEndian.PutUint16(packet[offset+2:offset+4], flow.sourcePort)
	binary.BigEndian.PutUint32(packet[offset+4:offset+8], sequence)
	binary.BigEndian.PutUint32(packet[offset+8:offset+12], acknowledgement)
	packet[offset+12] = 5 << 4
	packet[offset+13] = flags
	binary.BigEndian.PutUint16(packet[offset+14:offset+16], 0xffff)
	copy(packet[offset+tcpHeaderLength:], payload)
	setDirectTransportChecksum(packet, offset, version, 6)
	return packet, nil
}

func buildDirectUDPReply(flow prismFlowKey, version uint8, payload []byte, ipID uint16) ([]byte, error) {
	ipHeaderLength := 20
	if version == 6 {
		ipHeaderLength = 40
	}
	packet := make([]byte, ipHeaderLength+8+len(payload))
	if err := writeDirectIPHeader(packet, flow.destination, flow.source, version, 17, ipID); err != nil {
		return nil, err
	}
	offset := ipHeaderLength
	binary.BigEndian.PutUint16(packet[offset:offset+2], flow.destPort)
	binary.BigEndian.PutUint16(packet[offset+2:offset+4], flow.sourcePort)
	binary.BigEndian.PutUint16(packet[offset+4:offset+6], uint16(8+len(payload)))
	copy(packet[offset+8:], payload)
	setDirectTransportChecksum(packet, offset, version, 17)
	return packet, nil
}

func writeDirectIPHeader(packet []byte, source, destination netip.Addr, version, protocol uint8, ipID uint16) error {
	source, destination = source.Unmap(), destination.Unmap()
	switch version {
	case 4:
		if !source.Is4() || !destination.Is4() || len(packet) > 0xffff {
			return errUnsupportedProtectionPacket
		}
		packet[0] = 0x45
		binary.BigEndian.PutUint16(packet[2:4], uint16(len(packet)))
		binary.BigEndian.PutUint16(packet[4:6], ipID)
		binary.BigEndian.PutUint16(packet[6:8], 0x4000)
		packet[8], packet[9] = 64, protocol
		sourceBytes, destinationBytes := source.As4(), destination.As4()
		copy(packet[12:16], sourceBytes[:])
		copy(packet[16:20], destinationBytes[:])
		binary.BigEndian.PutUint16(packet[10:12], internetChecksum(packet[:20], 0))
	case 6:
		if !source.Is6() || !destination.Is6() || len(packet)-40 > 0xffff {
			return errUnsupportedProtectionPacket
		}
		packet[0] = 0x60
		binary.BigEndian.PutUint16(packet[4:6], uint16(len(packet)-40))
		packet[6], packet[7] = protocol, 64
		sourceBytes, destinationBytes := source.As16(), destination.As16()
		copy(packet[8:24], sourceBytes[:])
		copy(packet[24:40], destinationBytes[:])
	default:
		return errUnsupportedProtectionPacket
	}
	return nil
}

func setDirectTransportChecksum(packet []byte, offset int, version, protocol uint8) {
	checksumOffset := offset + 16
	if protocol == 17 {
		checksumOffset = offset + 6
	}
	packet[checksumOffset], packet[checksumOffset+1] = 0, 0
	length := len(packet) - offset
	var initial uint32
	if version == 4 {
		initial = checksumSum(packet[12:20], 0) + uint32(protocol) + uint32(length)
	} else {
		initial = checksumSum(packet[8:40], 0)
		initial += uint32(length>>16) + uint32(length&0xffff) + uint32(protocol)
	}
	checksum := internetChecksum(packet[offset:], initial)
	if protocol == 17 && checksum == 0 {
		checksum = 0xffff
	}
	binary.BigEndian.PutUint16(packet[checksumOffset:checksumOffset+2], checksum)
}

func directSequenceBefore(left, right uint32) bool { return int32(left-right) < 0 }
func directSequenceAfter(left, right uint32) bool  { return int32(left-right) > 0 }
