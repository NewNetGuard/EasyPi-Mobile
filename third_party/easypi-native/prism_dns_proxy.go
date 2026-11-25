package easypinative

import (
	"bytes"
	"encoding/binary"
	"fmt"
	"io"
	"net/http"
	"sync"
	"time"
)

var prismDNSHTTPClient = &http.Client{Timeout: 8 * time.Second}

const maxPrismDNSWorkers = 32

var prismDNSWorkers = make(chan struct{}, maxPrismDNSWorkers)
var prismDNSBudget = struct {
	sync.Mutex
	second int64
	count  int
}{}

func admitPrismDNS(now time.Time) bool {
	prismDNSBudget.Lock()
	defer prismDNSBudget.Unlock()
	if prismDNSBudget.second != now.Unix() {
		prismDNSBudget.second = now.Unix()
		prismDNSBudget.count = 0
	}
	if prismDNSBudget.count >= 128 {
		return false
	}
	select {
	case prismDNSWorkers <- struct{}{}:
		prismDNSBudget.count++
		return true
	default:
		return false
	}
}

// prismInterceptDNS owns plaintext UDP DNS inside the single Android TUN. It
// waits for the authenticated EasyPi policy off the packet loop, resolves
// allowed queries through DoH, and injects the response back into that TUN.
func prismInterceptDNS(packet []byte, inject func([]byte) error, now time.Time) bool {
	return prismInterceptDNSVia(packet, inject, now, resolveDNSOverHTTPS)
}

func prismInterceptDNSVia(packet []byte, inject func([]byte) error, now time.Time, resolve func([]byte) ([]byte, error), inspection ...bool) bool {
	protected := len(inspection) == 0 || inspection[0]
	flow, payload, ok := parsePrismTransport(packet)
	return prismInterceptDNSViaParsed(packet, flow, payload, ok, inject, now, resolve, protected)
}

func prismInterceptDNSViaParsed(packet []byte, flow prismFlowKey, payload []byte, ok bool, inject func([]byte) error, now time.Time, resolve func([]byte) ([]byte, error), protected bool) bool {
	if !ok || flow.protocol != 17 || flow.destPort != 53 {
		return false
	}
	domain, ok := parseDNSQuestion(payload)
	if !ok {
		return false
	}
	prismReportQuery(domain)
	if !admitPrismDNS(now) {
		prismReportDNSResult(domain, false, now)
		if response, err := dnsResponsePacket(packet, dnsErrorResponse(payload, 2)); err == nil {
			_ = inject(response)
		}
		return true
	}
	original := append([]byte(nil), packet...)
	query := append([]byte(nil), payload...)
	go func() {
		defer func() { <-prismDNSWorkers }()
		var answer []byte
		blocked := false
		if protected && (prismDomainPolicyCheck(domain) || looksLikeAdvertisingHost(domain)) {
			blocked = true
			answer = dnsErrorResponse(query, 3)
			prismReportBlocked(flow, domain, "policy", now)
		} else if end := dnsQuestionEnd(query); protected && end >= 4 && binary.BigEndian.Uint16(query[end-4:end-2]) == 65 {
			// Do not advertise ECH for a mode that requires a visible domain.
			// Payload TLS remains encrypted; an empty HTTPS RRset uses A/AAAA.
			answer = dnsErrorResponse(query, 0)
		} else {
			var err error
			answer, err = resolve(query)
			if err != nil {
				answer = dnsErrorResponse(query, 2)
			}
		}
		prismReportDNSResult(domain, blocked, now)
		if response, err := dnsResponsePacket(original, answer); err == nil {
			_ = inject(response)
		}
	}()
	return true
}

func resolveDNSOverHTTPS(query []byte) ([]byte, error) {
	request, err := http.NewRequest(http.MethodPost, "https://cloudflare-dns.com/dns-query", bytes.NewReader(query))
	if err != nil {
		return nil, err
	}
	request.Header.Set("Accept", "application/dns-message")
	request.Header.Set("Content-Type", "application/dns-message")
	response, err := prismDNSHTTPClient.Do(request)
	if err != nil {
		return nil, err
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("DoH returned HTTP %d", response.StatusCode)
	}
	answer, err := io.ReadAll(io.LimitReader(response.Body, 65536))
	if err != nil || len(answer) < 12 {
		return nil, fmt.Errorf("invalid DoH response")
	}
	return answer, nil
}

func dnsErrorResponse(query []byte, responseCode uint16) []byte {
	end := dnsQuestionEnd(query)
	if end == 0 {
		end = min(len(query), 12)
	}
	response := append([]byte(nil), query[:end]...)
	if len(response) < 12 {
		return response
	}
	requestFlags := binary.BigEndian.Uint16(response[2:4])
	binary.BigEndian.PutUint16(response[2:4], 0x8080|(requestFlags&0x7900)|(responseCode&0x000f))
	binary.BigEndian.PutUint16(response[6:8], 0)
	binary.BigEndian.PutUint16(response[8:10], 0)
	binary.BigEndian.PutUint16(response[10:12], 0)
	return response
}

func dnsQuestionEnd(message []byte) int {
	if len(message) < 17 || binary.BigEndian.Uint16(message[4:6]) == 0 {
		return 0
	}
	position := 12
	for position < len(message) {
		size := int(message[position])
		position++
		if size == 0 {
			if position+4 <= len(message) {
				return position + 4
			}
			return 0
		}
		if size > 63 || position+size > len(message) {
			return 0
		}
		position += size
	}
	return 0
}

func dnsResponsePacket(request, dns []byte) ([]byte, error) {
	if len(request) < 28 || len(dns) < 12 {
		return nil, fmt.Errorf("short DNS packet")
	}
	switch request[0] >> 4 {
	case 4:
		headerLength := int(request[0]&0x0f) * 4
		if headerLength < 20 || len(request) < headerLength+8 {
			return nil, fmt.Errorf("invalid IPv4 DNS packet")
		}
		response := make([]byte, headerLength+8+len(dns))
		copy(response[:headerLength], request[:headerLength])
		copy(response[12:16], request[16:20])
		copy(response[16:20], request[12:16])
		binary.BigEndian.PutUint16(response[2:4], uint16(len(response)))
		binary.BigEndian.PutUint16(response[6:8], 0)
		response[8] = 64
		response[10], response[11] = 0, 0
		binary.BigEndian.PutUint16(response[10:12], internetChecksum(response[:headerLength], 0))
		writeUDPResponse(response, headerLength, request, headerLength, dns)
		setUDPChecksumIPv4(response, headerLength)
		return response, nil
	case 6:
		if len(request) < 48 {
			return nil, fmt.Errorf("invalid IPv6 DNS packet")
		}
		response := make([]byte, 40+8+len(dns))
		copy(response[:40], request[:40])
		copy(response[8:24], request[24:40])
		copy(response[24:40], request[8:24])
		binary.BigEndian.PutUint16(response[4:6], uint16(8+len(dns)))
		response[7] = 64
		writeUDPResponse(response, 40, request, 40, dns)
		setUDPChecksumIPv6(response, 40)
		return response, nil
	default:
		return nil, fmt.Errorf("unsupported DNS IP version")
	}
}

func writeUDPResponse(response []byte, offset int, request []byte, requestOffset int, dns []byte) {
	copy(response[offset:offset+2], request[requestOffset+2:requestOffset+4])
	copy(response[offset+2:offset+4], request[requestOffset:requestOffset+2])
	binary.BigEndian.PutUint16(response[offset+4:offset+6], uint16(8+len(dns)))
	response[offset+6], response[offset+7] = 0, 0
	copy(response[offset+8:], dns)
}

func setUDPChecksumIPv4(packet []byte, offset int) {
	initial := checksumSum(packet[12:20], 0)
	initial += uint32(packet[9])
	initial += uint32(len(packet) - offset)
	binary.BigEndian.PutUint16(packet[offset+6:offset+8], internetChecksum(packet[offset:], initial))
}

func setUDPChecksumIPv6(packet []byte, offset int) {
	initial := checksumSum(packet[8:40], 0)
	length := uint32(len(packet) - offset)
	initial += uint32(length>>16) + uint32(length&0xffff) + uint32(packet[6])
	checksum := internetChecksum(packet[offset:], initial)
	if checksum == 0 {
		checksum = 0xffff
	}
	binary.BigEndian.PutUint16(packet[offset+6:offset+8], checksum)
}

func checksumSum(data []byte, initial uint32) uint32 {
	sum := initial
	for len(data) >= 2 {
		sum += uint32(binary.BigEndian.Uint16(data[:2]))
		data = data[2:]
	}
	if len(data) == 1 {
		sum += uint32(data[0]) << 8
	}
	return sum
}

func internetChecksum(data []byte, initial uint32) uint16 {
	sum := checksumSum(data, initial)
	for sum>>16 != 0 {
		sum = (sum & 0xffff) + (sum >> 16)
	}
	return ^uint16(sum)
}
