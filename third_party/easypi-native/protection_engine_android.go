//go:build android

package easypinative

import (
	"crypto/rand"
	"encoding/binary"
	"fmt"
	"io"
	"net"
	"net/netip"
	"os"
	"strconv"
	"sync"
	"sync/atomic"
	"time"
)

const (
	protectionTCPChunk = 1360
	protectionTCPQueue = 256
	// The engine does not negotiate TCP window scaling with Android. Keep the
	// maximum unacknowledged payload below the advertised 65,535-byte window.
	protectionTCPMaxOutstanding = 40
	protectionTCPRetransmit     = 200 * time.Millisecond
	protectionTCPIdle           = 5 * time.Minute
	protectionUDPIdle           = 60 * time.Second
	protectionMaxTCPFlows       = 2048
	protectionMaxUDPFlows       = 1024
)

type easyPiProtectionEngine struct {
	file    *os.File
	stop    chan struct{}
	stopped atomic.Bool
	wg      sync.WaitGroup
	write   sync.Mutex
	mu      sync.Mutex
	tcp     map[prismFlowKey]*easyPiTCPFlow
	udp     map[prismFlowKey]*easyPiUDPFlow
	ipID    atomic.Uint32
}

type easyPiTCPSegment struct {
	packet  []byte
	end     uint32
	sentAt  time.Time
	retries int
}

type easyPiTCPFlow struct {
	engine     *easyPiProtectionEngine
	key        prismFlowKey
	version    uint8
	mu         sync.Mutex
	conn       net.Conn
	ready      bool
	closed     bool
	clientNext uint32
	serverNext uint32
	unacked    []easyPiTCPSegment
	clientFIN  bool
	serverFIN  bool
	last       atomic.Int64
	toRemote   chan []byte
	done       chan struct{}
}

type easyPiUDPFlow struct {
	engine  *easyPiProtectionEngine
	key     prismFlowKey
	version uint8
	conn    *net.UDPConn
	last    atomic.Int64
	done    chan struct{}
	once    sync.Once
}

var easyPiProtection = struct {
	sync.Mutex
	engine *easyPiProtectionEngine
}{}

// StartEasyPiProtectionTun starts the EasyPi-owned, direct Protection data
// plane. Ownership of tunFD transfers to this function on every return path.
func StartEasyPiProtectionTun(tunFD int) error {
	if tunFD < 0 {
		return fmt.Errorf("invalid TUN file descriptor")
	}
	StopEasyPiProtectionTun()
	engine := &easyPiProtectionEngine{
		file: os.NewFile(uintptr(tunFD), "easypi-protection-tun"),
		stop: make(chan struct{}),
		tcp:  make(map[prismFlowKey]*easyPiTCPFlow),
		udp:  make(map[prismFlowKey]*easyPiUDPFlow),
	}
	if engine.file == nil {
		return fmt.Errorf("adopt TUN descriptor")
	}
	easyPiProtection.Lock()
	easyPiProtection.engine = engine
	easyPiProtection.Unlock()
	engine.wg.Add(2)
	go engine.packetPump()
	go engine.reaper()
	return nil
}

// StopEasyPiProtectionTun closes the TUN and every physical-network socket.
func StopEasyPiProtectionTun() {
	easyPiProtection.Lock()
	engine := easyPiProtection.engine
	easyPiProtection.engine = nil
	easyPiProtection.Unlock()
	if engine == nil || !engine.stopped.CompareAndSwap(false, true) {
		return
	}
	close(engine.stop)
	_ = engine.file.Close()
	engine.mu.Lock()
	tcpFlows := make([]*easyPiTCPFlow, 0, len(engine.tcp))
	for _, flow := range engine.tcp {
		tcpFlows = append(tcpFlows, flow)
	}
	udpFlows := make([]*easyPiUDPFlow, 0, len(engine.udp))
	for _, flow := range engine.udp {
		udpFlows = append(udpFlows, flow)
	}
	clear(engine.tcp)
	clear(engine.udp)
	engine.mu.Unlock()
	for _, flow := range tcpFlows {
		flow.close()
	}
	for _, flow := range udpFlows {
		flow.close()
	}
	finished := make(chan struct{})
	go func() { engine.wg.Wait(); close(finished) }()
	select {
	case <-finished:
	case <-time.After(2 * time.Second):
	}
	ClearPrismFlowRules()
	clearPrismBehaviorState()
}

func (engine *easyPiProtectionEngine) packetPump() {
	defer engine.wg.Done()
	buffer := make([]byte, 65535)
	for {
		n, err := engine.file.Read(buffer)
		if err != nil {
			return
		}
		if n == 0 {
			continue
		}
		engine.dispatch(append([]byte(nil), buffer[:n]...), true)
	}
}

func (engine *easyPiProtectionEngine) dispatch(packet []byte, mayHoldTLS bool) {
	if engine.stopped.Load() {
		return
	}
	var release func([]byte)
	if mayHoldTLS {
		release = func(held []byte) {
			engine.dispatch(append([]byte(nil), held...), false)
		}
	}
	if prismFilterOutboundVia(packet, engine.inject, time.Now(), resolveDNSOverHTTPS, release) {
		return
	}
	view, err := parseDirectPacket(packet)
	if err != nil {
		return
	}
	switch view.flow.protocol {
	case 6:
		engine.handleTCP(view)
	case 17:
		engine.handleUDP(view)
	}
}

func (engine *easyPiProtectionEngine) inject(packet []byte) error {
	if engine.stopped.Load() {
		return os.ErrClosed
	}
	engine.write.Lock()
	defer engine.write.Unlock()
	_, err := engine.file.Write(packet)
	return err
}

func (engine *easyPiProtectionEngine) nextIPID() uint16 {
	return uint16(engine.ipID.Add(1))
}

func (engine *easyPiProtectionEngine) handleTCP(packet directPacketView) {
	engine.mu.Lock()
	flow := engine.tcp[packet.flow]
	if flow == nil && packet.tcpFlags&directTCPFlagSYN != 0 && packet.tcpFlags&directTCPFlagACK == 0 {
		if len(engine.tcp) >= protectionMaxTCPFlows {
			engine.mu.Unlock()
			engine.rejectTCP(packet)
			return
		}
		flow = &easyPiTCPFlow{
			engine: engine, key: packet.flow, version: packet.version,
			clientNext: packet.sequence + 1, serverNext: randomProtectionSequence(),
			toRemote: make(chan []byte, protectionTCPQueue), done: make(chan struct{}),
		}
		flow.last.Store(time.Now().UnixNano())
		engine.tcp[packet.flow] = flow
		go flow.connect()
	}
	engine.mu.Unlock()
	if flow != nil {
		flow.handle(packet)
	}
}

func (flow *easyPiTCPFlow) connect() {
	network := "tcp6"
	if flow.key.destination.Is4() {
		network = "tcp4"
	}
	address := net.JoinHostPort(flow.key.destination.String(), strconv.Itoa(int(flow.key.destPort)))
	conn, err := (&net.Dialer{Timeout: 10 * time.Second, KeepAlive: 30 * time.Second}).Dial(network, address)
	if err != nil {
		flow.sendReset()
		flow.close()
		return
	}
	if tcp, ok := conn.(*net.TCPConn); ok {
		_ = tcp.SetNoDelay(true)
		_ = tcp.SetKeepAlive(true)
	}
	flow.mu.Lock()
	if flow.closed {
		flow.mu.Unlock()
		_ = conn.Close()
		return
	}
	flow.conn, flow.ready = conn, true
	flow.last.Store(time.Now().UnixNano())
	flow.mu.Unlock()
	flow.sendTracked(directTCPFlagSYN|directTCPFlagACK, nil)
	go flow.remoteWriter()
	go flow.remoteReader()
	go flow.retransmitter()
}

func (flow *easyPiTCPFlow) handle(packet directPacketView) {
	flow.last.Store(time.Now().UnixNano())
	if packet.tcpFlags&directTCPFlagRST != 0 {
		flow.close()
		return
	}
	flow.mu.Lock()
	if flow.closed {
		flow.mu.Unlock()
		return
	}
	ready := flow.ready
	if packet.tcpFlags&directTCPFlagSYN != 0 {
		if ready && len(flow.unacked) > 0 {
			retry := append([]byte(nil), flow.unacked[0].packet...)
			flow.mu.Unlock()
			_ = flow.engine.inject(retry)
			return
		}
		flow.mu.Unlock()
		return
	}
	if packet.tcpFlags&directTCPFlagACK != 0 {
		flow.acknowledgeLocked(packet.acknowledgement)
	}
	ackNeeded := false
	if len(packet.payload) > 0 {
		switch {
		case packet.sequence == flow.clientNext:
			payload := append([]byte(nil), packet.payload...)
			select {
			case flow.toRemote <- payload:
				flow.clientNext += uint32(len(payload))
				ackNeeded = true
			default:
				// Keep clientNext unchanged. Android will retransmit after the
				// bounded application-side queue has room.
			}
		case directSequenceBefore(packet.sequence, flow.clientNext):
			ackNeeded = true
		default:
			ackNeeded = true
		}
	}
	fin := packet.tcpFlags&directTCPFlagFIN != 0
	if fin && packet.sequence+uint32(len(packet.payload)) == flow.clientNext {
		flow.clientNext++
		flow.clientFIN = true
		ackNeeded = true
	}
	fullyClosed := flow.clientFIN && flow.serverFIN && len(flow.unacked) == 0
	flow.mu.Unlock()
	if ackNeeded {
		flow.sendACK()
	}
	if fin {
		flow.mu.Lock()
		conn := flow.conn
		flow.mu.Unlock()
		if tcp, ok := conn.(*net.TCPConn); ok {
			_ = tcp.CloseWrite()
		}
	}
	if fullyClosed {
		flow.close()
	}
}

func (flow *easyPiTCPFlow) acknowledgeLocked(ack uint32) {
	cut := 0
	for cut < len(flow.unacked) && !directSequenceAfter(flow.unacked[cut].end, ack) {
		cut++
	}
	if cut > 0 {
		copy(flow.unacked, flow.unacked[cut:])
		flow.unacked = flow.unacked[:len(flow.unacked)-cut]
	}
}

func (flow *easyPiTCPFlow) remoteWriter() {
	for {
		select {
		case <-flow.done:
			return
		case payload := <-flow.toRemote:
			flow.last.Store(time.Now().UnixNano())
			flow.mu.Lock()
			conn := flow.conn
			flow.mu.Unlock()
			if conn == nil {
				flow.close()
				return
			}
			if _, err := conn.Write(payload); err != nil {
				flow.sendReset()
				flow.close()
				return
			}
		}
	}
}

func (flow *easyPiTCPFlow) remoteReader() {
	buffer := make([]byte, 64*1024)
	for {
		flow.mu.Lock()
		conn := flow.conn
		flow.mu.Unlock()
		if conn == nil {
			return
		}
		n, err := conn.Read(buffer)
		if n > 0 {
			flow.last.Store(time.Now().UnixNano())
		}
		for start := 0; start < n; start += protectionTCPChunk {
			end := min(start+protectionTCPChunk, n)
			if !flow.waitForSendWindow() {
				return
			}
			flow.sendTracked(directTCPFlagACK|directTCPFlagPSH, buffer[start:end])
		}
		if err != nil {
			if err == io.EOF {
				flow.sendTracked(directTCPFlagFIN|directTCPFlagACK, nil)
			} else {
				flow.sendReset()
			}
			return
		}
	}
}

func (flow *easyPiTCPFlow) waitForSendWindow() bool {
	for {
		flow.mu.Lock()
		available := !flow.closed && len(flow.unacked) < protectionTCPMaxOutstanding
		flow.mu.Unlock()
		if available {
			return true
		}
		select {
		case <-flow.done:
			return false
		case <-time.After(2 * time.Millisecond):
		}
	}
}

func (flow *easyPiTCPFlow) sendTracked(flags uint8, payload []byte) {
	flow.mu.Lock()
	if flow.closed {
		flow.mu.Unlock()
		return
	}
	sequence, acknowledgement := flow.serverNext, flow.clientNext
	advance := uint32(len(payload))
	if flags&(directTCPFlagSYN|directTCPFlagFIN) != 0 {
		advance++
	}
	packet, err := buildDirectTCPPacket(
		flow.key, flow.version, sequence, acknowledgement, flags,
		append([]byte(nil), payload...), flow.engine.nextIPID(),
	)
	if err != nil {
		flow.mu.Unlock()
		flow.close()
		return
	}
	flow.serverNext += advance
	if flags&directTCPFlagFIN != 0 {
		flow.serverFIN = true
	}
	flow.unacked = append(flow.unacked, easyPiTCPSegment{
		packet: append([]byte(nil), packet...), end: sequence + advance, sentAt: time.Now(),
	})
	flow.mu.Unlock()
	_ = flow.engine.inject(packet)
}

func (flow *easyPiTCPFlow) sendACK() {
	flow.mu.Lock()
	if flow.closed {
		flow.mu.Unlock()
		return
	}
	packet, err := buildDirectTCPPacket(
		flow.key, flow.version, flow.serverNext, flow.clientNext,
		directTCPFlagACK, nil, flow.engine.nextIPID(),
	)
	flow.mu.Unlock()
	if err == nil {
		_ = flow.engine.inject(packet)
	}
}

func (flow *easyPiTCPFlow) sendReset() {
	flow.mu.Lock()
	if flow.closed {
		flow.mu.Unlock()
		return
	}
	packet, err := buildDirectTCPPacket(
		flow.key, flow.version, flow.serverNext, flow.clientNext,
		directTCPFlagRST|directTCPFlagACK, nil, flow.engine.nextIPID(),
	)
	flow.mu.Unlock()
	if err == nil {
		_ = flow.engine.inject(packet)
	}
}

func (flow *easyPiTCPFlow) retransmitter() {
	ticker := time.NewTicker(100 * time.Millisecond)
	defer ticker.Stop()
	for {
		select {
		case <-flow.done:
			return
		case now := <-ticker.C:
			var packets [][]byte
			failed := false
			flow.mu.Lock()
			for index := range flow.unacked {
				segment := &flow.unacked[index]
				if now.Sub(segment.sentAt) < protectionTCPRetransmit {
					continue
				}
				if segment.retries >= 8 {
					failed = true
					break
				}
				segment.retries++
				segment.sentAt = now
				packets = append(packets, append([]byte(nil), segment.packet...))
			}
			flow.mu.Unlock()
			if failed {
				flow.close()
				return
			}
			for _, packet := range packets {
				_ = flow.engine.inject(packet)
			}
		}
	}
}

func (flow *easyPiTCPFlow) close() {
	flow.mu.Lock()
	if flow.closed {
		flow.mu.Unlock()
		return
	}
	flow.closed = true
	close(flow.done)
	conn := flow.conn
	flow.conn = nil
	flow.mu.Unlock()
	if conn != nil {
		_ = conn.Close()
	}
	flow.engine.mu.Lock()
	if flow.engine.tcp[flow.key] == flow {
		delete(flow.engine.tcp, flow.key)
	}
	flow.engine.mu.Unlock()
}

func (engine *easyPiProtectionEngine) handleUDP(packet directPacketView) {
	engine.mu.Lock()
	flow := engine.udp[packet.flow]
	if flow == nil {
		if len(engine.udp) >= protectionMaxUDPFlows {
			engine.mu.Unlock()
			return
		}
		address := net.UDPAddrFromAddrPort(netipAddrPort(packet.flow.destination, packet.flow.destPort))
		network := "udp6"
		if packet.flow.destination.Is4() {
			network = "udp4"
		}
		conn, err := net.DialUDP(network, nil, address)
		if err == nil {
			flow = &easyPiUDPFlow{
				engine: engine, key: packet.flow, version: packet.version,
				conn: conn, done: make(chan struct{}),
			}
			flow.last.Store(time.Now().UnixNano())
			engine.udp[packet.flow] = flow
			go flow.reader()
		}
	}
	engine.mu.Unlock()
	if flow != nil {
		flow.last.Store(time.Now().UnixNano())
		_, _ = flow.conn.Write(packet.payload)
	}
}

func (engine *easyPiProtectionEngine) rejectTCP(packet directPacketView) {
	acknowledgement := packet.sequence + uint32(len(packet.payload))
	if packet.tcpFlags&(directTCPFlagSYN|directTCPFlagFIN) != 0 {
		acknowledgement++
	}
	response, err := buildDirectTCPPacket(
		packet.flow, packet.version, 0, acknowledgement,
		directTCPFlagRST|directTCPFlagACK, nil, engine.nextIPID(),
	)
	if err == nil {
		_ = engine.inject(response)
	}
}

func netipAddrPort(address netip.Addr, port uint16) netip.AddrPort {
	return netip.AddrPortFrom(address, port)
}

func (flow *easyPiUDPFlow) reader() {
	buffer := make([]byte, 65535)
	for {
		n, err := flow.conn.Read(buffer)
		if err != nil {
			flow.close()
			return
		}
		flow.last.Store(time.Now().UnixNano())
		packet, err := buildDirectUDPReply(flow.key, flow.version, buffer[:n], flow.engine.nextIPID())
		if err == nil {
			_ = flow.engine.inject(packet)
		}
	}
}

func (flow *easyPiUDPFlow) close() {
	flow.once.Do(func() {
		close(flow.done)
		_ = flow.conn.Close()
		flow.engine.mu.Lock()
		if flow.engine.udp[flow.key] == flow {
			delete(flow.engine.udp, flow.key)
		}
		flow.engine.mu.Unlock()
	})
}

func (engine *easyPiProtectionEngine) reaper() {
	defer engine.wg.Done()
	ticker := time.NewTicker(15 * time.Second)
	defer ticker.Stop()
	for {
		select {
		case <-engine.stop:
			return
		case now := <-ticker.C:
			engine.mu.Lock()
			var expired []*easyPiUDPFlow
			var expiredTCP []*easyPiTCPFlow
			for _, flow := range engine.tcp {
				if now.Sub(time.Unix(0, flow.last.Load())) >= protectionTCPIdle {
					expiredTCP = append(expiredTCP, flow)
				}
			}
			for _, flow := range engine.udp {
				if now.Sub(time.Unix(0, flow.last.Load())) >= protectionUDPIdle {
					expired = append(expired, flow)
				}
			}
			engine.mu.Unlock()
			for _, flow := range expiredTCP {
				flow.close()
			}
			for _, flow := range expired {
				flow.close()
			}
		}
	}
}

func randomProtectionSequence() uint32 {
	var value [4]byte
	if _, err := rand.Read(value[:]); err == nil {
		return binary.BigEndian.Uint32(value[:])
	}
	return uint32(time.Now().UnixNano())
}
