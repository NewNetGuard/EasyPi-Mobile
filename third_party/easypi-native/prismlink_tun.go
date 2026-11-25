//go:build android

package easypinative

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"os"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"chameleon/prismlink"
	"chameleon/shared"
	"golang.org/x/sys/unix"
)

const (
	prismLinkQueueCapacity = 512
	prismLinkPingInterval  = 5 * time.Second
	prismLinkUnresponsive  = 15 * time.Second
	prismLinkRekeyInterval = 30 * time.Minute
	prismLinkRekeyRetry    = 30 * time.Second
	prismLinkRekeyGrace    = 10 * time.Second
	prismLinkHelloAttempts = 5
	prismLinkSocketBuffer  = 4 << 20
	prismLinkDownlinkQueue = 1024
)

type prismLinkConfig struct {
	Address             string `json:"address"`
	PSKKey              string `json:"psk_key"`
	RelayAddress        string `json:"relay_address"`
	RelayInstallationID string `json:"relay_installation_id"`
	RelayRouteKey       string `json:"relay_route_key"`
	DirectAddress       string `json:"direct_address"`
	MTU                 int    `json:"mtu"`
}

type prismLinkStats struct {
	connected atomic.Bool
	bytesSent atomic.Uint64
	bytesRecv atomic.Uint64
	dropped   atomic.Uint64
	reconnect atomic.Uint64
	rttMillis atomic.Int64
}

type easyPiPrismLinkSession struct {
	file     *os.File
	stop     chan struct{}
	stopOnce sync.Once
	write    sync.Mutex
	outbound chan []byte
	wg       sync.WaitGroup
	config   prismLinkConfig
	identity prismlink.Credential
	relayID  [16]byte
	routeKey [32]byte
	stats    prismLinkStats
}

type prismLinkPath struct {
	conn     *net.UDPConn
	relay    bool
	relayID  [16]byte
	route    [8]byte
	clientID [16]byte
	routeKey [32]byte
}

var easyPiPrismLink = struct {
	sync.Mutex
	session *easyPiPrismLinkSession
}{}

func StartEasyPiPrismLink(tunFD int, configJSON string) error {
	if tunFD < 0 {
		return errors.New("invalid TUN file descriptor")
	}
	defer unix.Close(tunFD)
	StopEasyPiPrismLink()
	config, identity, relayID, err := parsePrismLinkConfig(configJSON)
	if err != nil {
		return err
	}
	routeKey, err := hex.DecodeString(config.RelayRouteKey)
	if err != nil || len(routeKey) != 32 {
		return errors.New("invalid PrismLink relay route key")
	}
	duplicate, err := unix.Dup(tunFD)
	if err != nil {
		return fmt.Errorf("duplicate TUN descriptor: %w", err)
	}
	session := &easyPiPrismLinkSession{
		file: os.NewFile(uintptr(duplicate), "easypi-prismlink-tun"), stop: make(chan struct{}),
		outbound: make(chan []byte, prismLinkQueueCapacity), config: config, identity: identity, relayID: relayID,
	}
	copy(session.routeKey[:], routeKey)
	session.wg.Add(2)
	easyPiPrismLink.Lock()
	easyPiPrismLink.session = session
	easyPiPrismLink.Unlock()
	go session.readTunnel()
	go session.runNetwork()
	return nil
}

func parsePrismLinkConfig(encoded string) (prismLinkConfig, prismlink.Credential, [16]byte, error) {
	var config prismLinkConfig
	var credential prismlink.Credential
	var relayID [16]byte
	decoder := json.NewDecoder(strings.NewReader(encoded))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(&config); err != nil {
		return config, credential, relayID, fmt.Errorf("decode PrismLink config: %w", err)
	}
	psk, err := hex.DecodeString(config.PSKKey)
	if err != nil || len(psk) != 32 {
		return config, credential, relayID, errors.New("invalid PrismLink credential")
	}
	copy(credential.PSK[:], psk)
	clientID, err := hex.DecodeString(shared.ClientIDForPSK(psk))
	if err != nil || len(clientID) != 16 {
		return config, credential, relayID, errors.New("invalid PrismLink client identity")
	}
	copy(credential.ClientID[:], clientID)
	ip, network, err := net.ParseCIDR(config.Address)
	if err != nil || ip.To4() == nil || network == nil {
		return config, credential, relayID, errors.New("invalid PrismLink tunnel address")
	}
	copy(credential.Address[:], ip.To4())
	relayBytes, err := hex.DecodeString(config.RelayInstallationID)
	if err != nil || len(relayBytes) != 16 {
		return config, credential, relayID, errors.New("invalid PrismLink relay identity")
	}
	copy(relayID[:], relayBytes)
	if config.RelayAddress == "" || config.MTU < 1200 || config.MTU > prismlink.MaxInnerPacket {
		return config, credential, relayID, errors.New("invalid PrismLink transport settings")
	}
	return config, credential, relayID, nil
}

func (session *easyPiPrismLinkSession) readTunnel() {
	defer session.wg.Done()
	buffer := make([]byte, 65535)
	enqueue := func(packet []byte) {
		select {
		case <-session.stop:
			return
		case session.outbound <- append([]byte(nil), packet...):
		default:
			session.stats.dropped.Add(1)
		}
	}
	// The home tunnel is a plain pipe: NetProtection on the home EasyPi is the
	// only policy authority, DNS included. Every packet, plaintext DNS to the
	// home resolver too, is carried unchanged; nothing is filtered or resolved
	// on the phone.
	for {
		n, err := session.file.Read(buffer)
		if err != nil {
			return
		}
		if n == 0 {
			continue
		}
		if n > prismlink.MaxInnerPacket {
			session.stats.dropped.Add(1)
			continue
		}
		enqueue(buffer[:n])
	}
}

func (session *easyPiPrismLinkSession) runNetwork() {
	defer session.wg.Done()
	backoff := 250 * time.Millisecond
	for {
		select {
		case <-session.stop:
			return
		default:
		}
		path, secure, err := session.connect()
		if err == nil {
			session.stats.connected.Store(true)
			backoff = 250 * time.Millisecond
			err = session.runConnected(path, secure)
			session.stats.connected.Store(false)
			_ = path.conn.Close()
		}
		session.stats.reconnect.Add(1)
		timer := time.NewTimer(backoff)
		select {
		case <-session.stop:
			timer.Stop()
			return
		case <-timer.C:
		}
		if backoff < 5*time.Second {
			backoff *= 2
		}
	}
}

func (session *easyPiPrismLinkSession) connect() (*prismLinkPath, *prismlink.Session, error) {
	type candidate struct {
		network string
		address string
		relay   bool
	}
	candidates := []candidate{}
	if strings.TrimSpace(session.config.DirectAddress) != "" {
		candidates = append(candidates, candidate{"udp4", session.config.DirectAddress, false})
	}
	// IPv6 first: the relay's IPv4 route into some mobile networks is lossy
	// (~22% loss into Telekom via Cogent) while its IPv6 route is clean
	// (~42 vs ~5 Mbit/s measured). Without IPv6 (no AAAA record or no IPv6
	// route) this fails at once and the IPv4 relay is used.
	candidates = append(candidates,
		candidate{"udp6", session.config.RelayAddress, true},
		candidate{"udp4", session.config.RelayAddress, true},
	)
	var lastError error
	for _, candidate := range candidates {
		path, secure, err := session.handshake(candidate.network, candidate.address, candidate.relay)
		if err == nil {
			return path, secure, nil
		}
		lastError = err
	}
	return nil, nil, lastError
}

func (session *easyPiPrismLinkSession) handshake(network, address string, relay bool) (*prismLinkPath, *prismlink.Session, error) {
	remote, err := net.ResolveUDPAddr(network, address)
	if err != nil {
		return nil, nil, err
	}
	conn, err := net.DialUDP(network, nil, remote)
	if err != nil {
		return nil, nil, err
	}
	// The direct address is the home EasyPi's LAN address. Away from home it
	// is unreachable (or another network's host), so do not spend the
	// handshake timeout on it or send it the hello: go to the relay at once.
	if !relay && !onSameLAN(conn.LocalAddr(), conn.RemoteAddr()) {
		_ = conn.Close()
		return nil, nil, errors.New("PrismLink direct path is not on this network")
	}
	// The default 208 KiB receive buffer overflows on download bursts (the
	// kernel dropped ~1.6% of tunnel packets on home Wi-Fi), and the inner TCP
	// then treats every drop as congestion. Android allows up to rmem_max.
	_ = conn.SetReadBuffer(prismLinkSocketBuffer)
	_ = conn.SetWriteBuffer(prismLinkSocketBuffer)
	path := &prismLinkPath{
		conn: conn, relay: relay, relayID: session.relayID,
		clientID: session.identity.ClientID, routeKey: session.routeKey,
	}
	for path.route == [8]byte{} {
		if _, err := rand.Read(path.route[:]); err != nil {
			_ = conn.Close()
			return nil, nil, err
		}
	}
	handshake, hello, err := prismlink.NewClientHandshake(session.identity, time.Now())
	if err != nil {
		_ = conn.Close()
		return nil, nil, err
	}
	buffer := make([]byte, prismlink.MaxDatagram)
	for attempt := 0; attempt < 3; attempt++ {
		// The relay delivers only to an address bound by this signed message.
		if err := path.bind(); err != nil {
			_ = conn.Close()
			return nil, nil, err
		}
		if err := path.send(hello); err != nil {
			last := err
			_ = conn.Close()
			return nil, nil, last
		}
		_ = conn.SetReadDeadline(time.Now().Add(time.Second))
		n, err := conn.Read(buffer)
		if err != nil {
			continue
		}
		secure, err := handshake.Complete(buffer[:n], time.Now())
		if err == nil {
			_ = conn.SetReadDeadline(time.Time{})
			return path, secure, nil
		}
	}
	_ = conn.Close()
	return nil, nil, errors.New("PrismLink handshake timed out")
}

func (session *easyPiPrismLinkSession) runConnected(path *prismLinkPath, secure *prismlink.Session) error {
	sessions := prismlink.NewSessionPair(secure)
	// Sessions are safe for concurrent use. The downlink is handled outside
	// the control loop below, so download and upload no longer share one
	// goroutine (that capped downloads near 50 Mbit/s); only server hellos
	// for a rekey reach the control loop.
	var lastReceived atomic.Int64
	lastReceived.Store(time.Now().UnixNano())
	serverHellos := make(chan []byte, 4)
	readError := make(chan error, 1)
	// Reading the socket and opening+writing to the TUN are two pipeline
	// stages on two goroutines; one goroutine doing both capped downloads.
	// A single opener keeps the packet order.
	type datagram struct {
		buffer *[]byte
		length int
	}
	buffers := sync.Pool{New: func() any {
		buffer := make([]byte, prismlink.MaxDatagram)
		return &buffer
	}}
	downlink := make(chan datagram, prismLinkDownlinkQueue)
	go func() {
		defer close(downlink)
		for {
			buffer := buffers.Get().(*[]byte)
			n, err := path.conn.Read(*buffer)
			if err != nil {
				buffers.Put(buffer)
				readError <- err
				return
			}
			if prismlink.IsServerHello((*buffer)[:n]) {
				select {
				case serverHellos <- append([]byte(nil), (*buffer)[:n]...):
				default:
				}
				buffers.Put(buffer)
				continue
			}
			select {
			case downlink <- datagram{buffer: buffer, length: n}:
			default:
				session.stats.dropped.Add(1)
				buffers.Put(buffer)
			}
		}
	}()
	go func() {
		for item := range downlink {
			now := time.Now()
			// Open copies the payload out, so the buffer is reusable at once.
			typ, payload, err := sessions.Open((*item.buffer)[:item.length], now)
			buffers.Put(item.buffer)
			if err != nil {
				continue
			}
			lastReceived.Store(now.UnixNano())
			switch typ {
			case prismlink.PacketData:
				if session.inject(payload) == nil {
					session.stats.bytesRecv.Add(uint64(len(payload)))
				}
			case prismlink.PacketPong:
				if len(payload) == 8 {
					sent := int64(uint64(payload[0])<<56 | uint64(payload[1])<<48 | uint64(payload[2])<<40 | uint64(payload[3])<<32 |
						uint64(payload[4])<<24 | uint64(payload[5])<<16 | uint64(payload[6])<<8 | uint64(payload[7]))
					session.stats.rttMillis.Store(time.Now().UnixMilli() - sent)
				}
			}
		}
	}()
	ping := time.NewTicker(prismLinkPingInterval)
	watchdog := time.NewTicker(time.Second)
	defer ping.Stop()
	defer watchdog.Stop()
	nextRekey := time.Now().Add(prismLinkRekeyInterval)
	// Keys are renewed on the established path, make before break: the
	// current session keeps carrying traffic until the new one is complete.
	var rekey *prismlink.ClientHandshake
	var rekeyHello []byte
	var rekeyAttempts int
	var rekeySentAt time.Time
	sendPing := func() error {
		now := time.Now().UnixMilli()
		payload := []byte{byte(now >> 56), byte(now >> 48), byte(now >> 40), byte(now >> 32), byte(now >> 24), byte(now >> 16), byte(now >> 8), byte(now)}
		sealed, err := sessions.Current().Seal(prismlink.PacketPing, payload)
		if err != nil {
			return err
		}
		return path.send(sealed)
	}
	for {
		select {
		case <-session.stop:
			return os.ErrClosed
		case err := <-readError:
			return err
		case packet := <-session.outbound:
			sealed, err := sessions.Current().Seal(prismlink.PacketData, packet)
			if err != nil || path.send(sealed) != nil {
				return errors.New("PrismLink send failed")
			}
			session.stats.bytesSent.Add(uint64(len(packet)))
		case packet := <-serverHellos:
			if rekey == nil {
				continue
			}
			now := time.Now()
			next, err := rekey.Complete(packet, now)
			if err != nil {
				continue
			}
			sessions.Promote(next, now, prismLinkRekeyGrace)
			rekey, rekeyHello = nil, nil
			nextRekey = now.Add(prismLinkRekeyInterval)
			lastReceived.Store(now.UnixNano())
			// The first packet of the new session moves the server's
			// downlink to it.
			if sendPing() != nil {
				return errors.New("PrismLink ping failed")
			}
		case <-ping.C:
			// Refresh the relay binding with every ping so a changed NAT
			// mapping is followed within one interval.
			if path.bind() != nil || sendPing() != nil {
				return errors.New("PrismLink ping failed")
			}
		case <-watchdog.C:
			now := time.Now()
			if now.Sub(time.Unix(0, lastReceived.Load())) > prismLinkUnresponsive {
				return errors.New("PrismLink path is unresponsive")
			}
			if rekey == nil && now.After(nextRekey) {
				next, hello, err := prismlink.NewClientHandshake(session.identity, now)
				if err != nil {
					nextRekey = now.Add(prismLinkRekeyRetry)
					continue
				}
				rekey, rekeyHello, rekeyAttempts, rekeySentAt = next, hello, 0, time.Time{}
			}
			if rekey != nil && now.Sub(rekeySentAt) >= time.Second {
				if rekeyAttempts >= prismLinkHelloAttempts {
					// Keep the working session and try again later.
					rekey, rekeyHello = nil, nil
					nextRekey = now.Add(prismLinkRekeyRetry)
					continue
				}
				rekeyAttempts++
				rekeySentAt = now
				if path.send(rekeyHello) != nil {
					return errors.New("PrismLink rekey send failed")
				}
			}
		}
	}
}

func (path *prismLinkPath) bind() error {
	if !path.relay {
		return nil
	}
	packet, err := prismlink.EncodeRelayMobileBind(path.relayID, path.route, path.clientID, path.routeKey, time.Now())
	if err != nil {
		return err
	}
	_, err = path.conn.Write(packet)
	return err
}

func (path *prismLinkPath) send(packet []byte) error {
	if path.relay {
		wrapped, err := prismlink.EncodeRelayData(prismlink.RelayMobileData, path.relayID, path.route, packet)
		if err != nil {
			return err
		}
		packet = wrapped
	}
	_, err := path.conn.Write(packet)
	return err
}

func (session *easyPiPrismLinkSession) inject(packet []byte) error {
	select {
	case <-session.stop:
		return os.ErrClosed
	default:
	}
	session.write.Lock()
	defer session.write.Unlock()
	_, err := session.file.Write(packet)
	return err
}

func StopEasyPiPrismLink() {
	easyPiPrismLink.Lock()
	session := easyPiPrismLink.session
	easyPiPrismLink.session = nil
	easyPiPrismLink.Unlock()
	if session == nil {
		return
	}
	session.stopOnce.Do(func() { close(session.stop) })
	_ = session.file.Close()
	finished := make(chan struct{})
	go func() { session.wg.Wait(); close(finished) }()
	select {
	case <-finished:
	case <-time.After(3 * time.Second):
	}
	ClearPrismFlowRules()
	clearPrismBehaviorState()
}

func EasyPiPrismLinkStats() string {
	easyPiPrismLink.Lock()
	session := easyPiPrismLink.session
	easyPiPrismLink.Unlock()
	if session == nil {
		return `{"connected":false,"bytes_sent":0,"bytes_recv":0,"dropped":0,"reconnects":0,"rtt_ms":0}`
	}
	return fmt.Sprintf(
		`{"connected":%t,"bytes_sent":%d,"bytes_recv":%d,"dropped":%d,"reconnects":%d,"rtt_ms":%d}`,
		session.stats.connected.Load(), session.stats.bytesSent.Load(), session.stats.bytesRecv.Load(),
		session.stats.dropped.Load(), session.stats.reconnect.Load(), session.stats.rttMillis.Load(),
	)
}
