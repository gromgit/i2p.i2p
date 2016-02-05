package net.i2p.sam;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

import net.i2p.I2PException;
import net.i2p.client.I2PSession;
import net.i2p.client.I2PSessionException;
import net.i2p.client.I2PSessionMuxedListener;
import net.i2p.client.streaming.I2PSocket;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.util.Log;

/**
 * A session that does nothing, but implements interfaces for raw, datagram, and streaming
 * for convenience.
 *
 * We extend SAMv3StreamSession as we must have it set up the I2PSession, in case
 * user adds a STREAM session (and he probably will).
 * This session receives all data from I2P, but you can't send any data on it.
 *
 * @since 0.9.25
 */
class MasterSession extends SAMv3StreamSession implements SAMDatagramReceiver, SAMRawReceiver,
                                                          SAMMessageSess, I2PSessionMuxedListener {
	private final SAMv3Handler handler;
	private final SAMv3DatagramServer dgs;
	private final Map<String, SAMMessageSess> sessions;

	/**
	 * Build a Session according to information
	 * registered with the given nickname
	 *   
	 * @param nick nickname of the session
	 * @throws IOException
	 * @throws DataFormatException
	 * @throws I2PSessionException
	 */
	public MasterSession(String nick, SAMv3DatagramServer dgServer, SAMv3Handler handler, Properties props) 
			throws IOException, DataFormatException, SAMException {
		super(nick);
		props.setProperty("net.i2p.streaming.enforceProtocol", "true");
                props.setProperty("i2cp.dontPublishLeaseSet", "false");
		props.setProperty("FROM_PORT", Integer.toString(I2PSession.PORT_UNSPECIFIED));
		props.setProperty("TO_PORT", Integer.toString(I2PSession.PORT_UNSPECIFIED));
		dgs = dgServer;
		sessions = new ConcurrentHashMap<String, SAMMessageSess>(4);
		this.handler = handler;
		I2PSession isess = socketMgr.getSession();
		// if we get a RAW session added with 0/0, it will replace this,
		// and we won't add this back if removed.
		isess.addMuxedSessionListener(this, I2PSession.PROTO_ANY, I2PSession.PORT_ANY);
	}

	/**
	 *  Add a session
	 *  @return null for success, or error message
	 */
	public synchronized String add(String nick, String style, Properties props) {
		SessionRecord rec = SAMv3Handler.sSessionsHash.get(nick);
		if (rec != null || sessions.containsKey(nick))
			return "Duplicate ID " + nick;
		int listenPort = I2PSession.PORT_ANY;
		String slp = (String) props.remove("LISTEN_PORT");
                if (slp == null)
                    slp = props.getProperty("FROM_PORT");
		if (slp != null) {
			try {
				listenPort = Integer.parseInt(slp);
				if (listenPort < 0 || listenPort > 65535)
					return "Bad LISTEN_PORT " + slp;
				// TODO enforce streaming listen port must be 0 or from port
			} catch (NumberFormatException nfe) {
				return "Bad LISTEN_PORT " + slp;
			}
		}
		int listenProtocol;
		SAMMessageSess sess;
		// temp
		try {
			I2PSession isess = socketMgr.getSession();
			if (style.equals("RAW")) {
				if (!props.containsKey("PORT"))
					return "RAW subsession must specify PORT";
				listenProtocol = I2PSession.PROTO_DATAGRAM_RAW;
				String spr = (String) props.remove("LISTEN_PROTOCOL");
	                        if (spr == null)
	                            spr = props.getProperty("PROTOCOL");
				if (spr != null) {
					try {
						listenProtocol = Integer.parseInt(spr);
						// RAW can't listen on streaming protocol
						if (listenProtocol < 0 || listenProtocol > 255 ||
						    listenProtocol == I2PSession.PROTO_STREAMING)
							return "Bad RAW LISTEN_PPROTOCOL " + spr;
					} catch (NumberFormatException nfe) {
						return "Bad LISTEN_PROTOCOL " + spr;
					}
				}
				sess = new SAMv3RawSession(nick, props, handler, isess, listenProtocol, listenPort, dgs);
			} else if (style.equals("DATAGRAM")) {
				if (!props.containsKey("PORT"))
					return "DATAGRAM subsession must specify PORT";
				listenProtocol = I2PSession.PROTO_DATAGRAM;
				sess = new SAMv3DatagramSession(nick, props, handler, isess, listenPort, dgs);
			} else if (style.equals("STREAM")) {
				listenProtocol = I2PSession.PROTO_STREAMING;
				// FIXME need something that hangs off an existing dest
				sess = new SAMv3StreamSession(nick, props, handler, socketMgr, listenPort);
			} else {
				return "Unrecognized SESSION STYLE " + style;
			}
		} catch (Exception e) {
			// temp
			return e.toString();
		}

		for (SAMMessageSess s : sessions.values()) {
			if (listenProtocol == s.getListenProtocol() &&
			    listenPort == s.getListenPort())
				return "Duplicate protocol " + listenProtocol + " and port " + listenPort;
		}

		// add to session db and our map
		rec = new SessionRecord(getDestination().toBase64(), props, handler);
		try {
			if (!SAMv3Handler.sSessionsHash.put(nick, rec))
				return "Duplicate ID " + nick;
			sessions.put(nick, sess);
		} catch (SessionsDB.ExistingIdException e) {
			return e.toString();
		} catch (SessionsDB.ExistingDestException e) {
			// fixme need new db method for dup dests
		}
		// listeners etc

		// all ok
		return null;
	}

	/**
	 *  Remove a session
	 *  @return null for success, or error message
	 */
	public synchronized String remove(String nick, Properties props) {
		boolean ok = SAMv3Handler.sSessionsHash.del(nick);
		SAMMessageSess sess = sessions.remove(nick);
		if (sess != null) {
			sess.close();
			// TODO if 0/0, add back this as listener?
		} else {
			ok = false;
		}
		if (!ok)
			return "ID " + nick + " not found";
		// all ok
		return null;
	}

	/**
	 *  @throws IOException always
	 */
	public void receiveDatagramBytes(Destination sender, byte[] data, int proto,
	                                 int fromPort, int toPort) throws IOException {
		throw new IOException("master session");
	}

	/**
	 *  Does nothing.
	 */
	public void stopDatagramReceiving() {}
	
	/**
	 *  @throws IOException always
	 */
	public void receiveRawBytes(byte[] data, int proto, int fromPort, int toPort) throws IOException {
		throw new IOException("master session");
	}

	/**
	 *  Does nothing.
	 */
	public void stopRawReceiving() {}



	/////// stream session overrides

	/** @throws I2PException always */
	@Override
	public void connect(SAMv3Handler handler, String dest, Properties props) throws I2PException {
		throw new I2PException("master session");
	}

	/** @throws SAMException always */
	@Override
	public void accept(SAMv3Handler handler, boolean verbose) throws SAMException {
		throw new SAMException("master session");
	}

	/** @throws SAMException always */
	@Override
	public void startForwardingIncoming(Properties props, boolean sendPorts) throws SAMException {
		throw new SAMException("master session");
	}

	/** does nothing */
	@Override
	public void stopForwardingIncoming() {}


	///// SAMMessageSess interface

	@Override
	public int getListenProtocol() {
	    return I2PSession.PROTO_ANY;
	}

	@Override
	public int getListenPort() {
	    return I2PSession.PORT_ANY;
	}

	/**
	 * Close the master session
	 */
	@Override
	public void close() {
		// close sessions?
		super.close();
	}
        
	// I2PSessionMuxedImpl interface

        public void disconnected(I2PSession session) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("I2P session disconnected");
            close();
        }

        public void errorOccurred(I2PSession session, String message,
                                  Throwable error) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("I2P error: " + message, error);
            close();
        }
            
        public void messageAvailable(I2PSession session, int msgId, long size) {
            messageAvailable(session, msgId, size, I2PSession.PROTO_UNSPECIFIED,
                             I2PSession.PORT_UNSPECIFIED, I2PSession.PORT_UNSPECIFIED);
        }

        /** @since 0.9.24 */
        public void messageAvailable(I2PSession session, int msgId, long size,
                                     int proto, int fromPort, int toPort) {
            try {
                byte msg[] = session.receiveMessage(msgId);
                if (msg == null)
                    return;
                messageReceived(msg, proto, fromPort, toPort);
            } catch (I2PSessionException e) {
                _log.error("Error fetching I2P message", e);
                close();
            }
        }
        
        public void reportAbuse(I2PSession session, int severity) {
            _log.warn("Abuse reported (severity: " + severity + ")");
            close();
        }

	private void messageReceived(byte[] msg, int proto, int fromPort, int toPort) {
		if (_log.shouldWarn())
			_log.warn("Unhandled message received, length = " + msg.length +
				" protocol: " + proto + " from port: " + fromPort + " to port: " + toPort);
	}

	private class StreamAcceptor implements Runnable {
		
		public StreamAcceptor() {
		}
		
		public void run() {
			while (getSocketServer()!=null) {
				
				// wait and accept a connection from I2P side
				I2PSocket i2ps;
				try {
					i2ps = getSocketServer().accept();
					if (i2ps == null)
						continue;
				} catch (SocketTimeoutException ste) {
					continue;
				} catch (ConnectException ce) {
					if (_log.shouldLog(Log.WARN))
						_log.warn("Error accepting", ce);
					try { Thread.sleep(50); } catch (InterruptedException ie) {}
					continue;
				} catch (I2PException ipe) {
					if (_log.shouldLog(Log.WARN))
						_log.warn("Error accepting", ipe);
					break;
				}
				int port = i2ps.getLocalPort();
				SAMMessageSess foundSess = null;
				Collection<SAMMessageSess> all = sessions.values();
				for (Iterator<SAMMessageSess> iter = all.iterator(); iter.hasNext(); ) {
					SAMMessageSess sess = iter.next();
					if (sess.getListenProtocol() != I2PSession.PROTO_STREAMING) {
						// remove as we may be going around again below
						iter.remove();
						continue;
					}
					if (sess.getListenPort() == port) {
						foundSess = sess;
						break;
					}
				}
				// We never send streaming out as a raw packet to a default listener,
				// and we don't allow raw to listen on streaming protocol,
				// so we don't have to look for a default protocol,
				// but we do have to look for a default port listener.
				if (foundSess == null) {
					for (SAMMessageSess sess : all) {
						if (sess.getListenPort() == 0) {
							foundSess = sess;
							break;
						}
					}
				}
				if (foundSess != null) {
					SAMv3StreamSession ssess = (SAMv3StreamSession) foundSess;
					boolean ok = ssess.queueSocket(i2ps);
					if (!ok) {
						_log.logAlways(Log.WARN, "Accept queue overflow for " + ssess);
						try { i2ps.close(); } catch (IOException ioe) {}
					}
				} else {
					if (_log.shouldLog(Log.WARN))
						_log.warn("No subsession found for incoming streaming connection on port " + port);
				}
			}
		}
	}
}
