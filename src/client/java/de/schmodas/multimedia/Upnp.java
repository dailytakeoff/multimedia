package de.schmodas.multimedia;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Automatic port forwarding on the home router (UPnP IGD), so friends behind networks that block hole punching
 * – phone hotspots, many mobile and some school networks – can still connect: they connect out to this PC's
 * forwarded port, and the answer gets back to them. Only talks to the router in the local network; does nothing
 * if the router has UPnP turned off. Mappings are temporary (1 hour, renewed while in use) and removed again.
 */
final class Upnp {
	private static final String[] SEARCH = {
			"urn:schemas-upnp-org:device:InternetGatewayDevice:1",
			"urn:schemas-upnp-org:service:WANIPConnection:1",
			"urn:schemas-upnp-org:service:WANIPConnection:2",
			"urn:schemas-upnp-org:service:WANPPPConnection:1",
	};
	private static final Pattern LOCATION = Pattern.compile("(?im)^location:\\s*(\\S+)");
	private static final Pattern SERVICE = Pattern.compile("(?is)<service>(.*?)</service>");
	private static final Pattern TYPE = Pattern.compile("(?is)<serviceType>\\s*(urn:schemas-upnp-org:service:WAN(?:IP|PPP)Connection:\\d)\\s*</serviceType>");
	private static final Pattern CONTROL = Pattern.compile("(?is)<controlURL>\\s*([^<\\s]+)\\s*</controlURL>");
	private static final Pattern EXTERNAL_IP = Pattern.compile("(?is)<NewExternalIPAddress>\\s*([0-9.]+)\\s*</NewExternalIPAddress>");

	private record Gateway(URI control, String service, InetAddress lanAddress) {
	}

	private static volatile Gateway gateway;
	private static volatile long lastSearch;
	private static final HttpClient HTTP = HttpClient.newBuilder()
			.proxy(java.net.ProxySelector.of(null))
			.connectTimeout(Duration.ofSeconds(2))
			.build();

	private Upnp() {
	}

	/** Look for the router in the background at start-up, so connecting later doesn't wait for it. */
	static void warmUp() {
		Thread t = new Thread(() -> {
			synchronized (Upnp.class) {
				gateway();
			}
		}, "MultiMedia-UpnpSearch");
		t.setDaemon(true);
		t.start();
	}

	/** Forwards UDP localPort on the router; returns the public address friends can reach, or null. */
	static synchronized InetSocketAddress map(int localPort) {
		Gateway g = gateway();
		if (g == null) {
			return null;
		}
		String ip = externalIp(g);
		if (ip == null || isPrivate(ip)) {
			return null; // router behind another NAT (double NAT / carrier NAT): forwarding wouldn't help
		}
		for (int external = localPort; external < localPort + 4; external++) {
			String body = "<NewRemoteHost></NewRemoteHost><NewExternalPort>" + external + "</NewExternalPort>"
					+ "<NewProtocol>UDP</NewProtocol><NewInternalPort>" + localPort + "</NewInternalPort>"
					+ "<NewInternalClient>" + g.lanAddress().getHostAddress() + "</NewInternalClient><NewEnabled>1</NewEnabled>"
					+ "<NewPortMappingDescription>Sharing Craft</NewPortMappingDescription><NewLeaseDuration>3600</NewLeaseDuration>";
			String reply = soap(g, "AddPortMapping", body);
			if (reply != null && !reply.contains("Fault")) {
				MultiMediaClient.LOGGER.info("UPnP: Port {} -> {}:{} freigegeben", external, ip, localPort);
				return new InetSocketAddress(ip, external);
			}
		}
		MultiMediaClient.LOGGER.info("UPnP: Router lehnt Portfreigabe ab");
		return null;
	}

	static void unmap(int externalPort) {
		Gateway g = gateway;
		if (g == null) {
			return;
		}
		soap(g, "DeletePortMapping", "<NewRemoteHost></NewRemoteHost><NewExternalPort>" + externalPort
				+ "</NewExternalPort><NewProtocol>UDP</NewProtocol>");
	}

	private static Gateway gateway() {
		if (gateway != null) {
			return gateway;
		}
		long now = System.currentTimeMillis();
		if (now - lastSearch < 60_000) {
			return null; // searched a minute ago and found nothing
		}
		lastSearch = now;
		Set<String> locations = new LinkedHashSet<>();
		try (DatagramSocket s = new DatagramSocket()) {
			s.setSoTimeout(700);
			InetSocketAddress group = new InetSocketAddress(InetAddress.getByName("239.255.255.250"), 1900);
			for (String st : SEARCH) {
				byte[] msg = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: "
						+ st + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
				s.send(new DatagramPacket(msg, msg.length, group));
			}
			long end = System.currentTimeMillis() + 2500;
			byte[] buf = new byte[2048];
			while (System.currentTimeMillis() < end) {
				DatagramPacket p = new DatagramPacket(buf, buf.length);
				try {
					s.receive(p);
				} catch (SocketTimeoutException e) {
					if (!locations.isEmpty()) {
						break;
					}
					continue;
				}
				Matcher m = LOCATION.matcher(new String(p.getData(), 0, p.getLength(), StandardCharsets.US_ASCII));
				if (m.find()) {
					String loc = m.group(1);
					// only the router in the local network, never something on the internet
					URI u = URI.create(loc);
					if ("http".equalsIgnoreCase(u.getScheme()) && u.getHost() != null && isPrivate(u.getHost())
							&& u.getHost().equals(p.getAddress().getHostAddress())) {
						locations.add(loc);
					}
				}
			}
		} catch (IOException | IllegalArgumentException e) {
			MultiMediaClient.LOGGER.info("UPnP: Suche ging nicht: {}", e.toString());
		}
		for (String loc : locations) {
			try {
				URI base = URI.create(loc);
				HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(base).timeout(Duration.ofSeconds(3)).GET().build(),
						HttpResponse.BodyHandlers.ofString());
				Matcher svc = SERVICE.matcher(r.body());
				while (svc.find()) {
					Matcher type = TYPE.matcher(svc.group(1));
					Matcher control = CONTROL.matcher(svc.group(1));
					if (type.find() && control.find()) {
						URI ctl = base.resolve(control.group(1));
						if (!isPrivate(ctl.getHost())) {
							continue;
						}
						InetAddress lan;
						try (DatagramSocket probe = new DatagramSocket()) {
							probe.connect(InetAddress.getByName(ctl.getHost()), ctl.getPort() > 0 ? ctl.getPort() : 80);
							lan = probe.getLocalAddress();
						}
						if (!(lan instanceof Inet4Address)) {
							continue;
						}
						gateway = new Gateway(ctl, type.group(1), lan);
						MultiMediaClient.LOGGER.info("UPnP: Router gefunden ({})", type.group(1));
						return gateway;
					}
				}
			} catch (Exception e) {
				MultiMediaClient.LOGGER.info("UPnP: {} nicht lesbar: {}", loc, e.toString());
			}
		}
		MultiMediaClient.LOGGER.info("UPnP: kein Router mit Portfreigabe gefunden");
		return null;
	}

	private static String externalIp(Gateway g) {
		String reply = soap(g, "GetExternalIPAddress", "");
		if (reply == null) {
			return null;
		}
		Matcher m = EXTERNAL_IP.matcher(reply);
		return m.find() ? m.group(1) : null;
	}

	private static String soap(Gateway g, String action, String args) {
		String body = "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" "
				+ "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body><u:" + action + " xmlns:u=\""
				+ g.service() + "\">" + args + "</u:" + action + "></s:Body></s:Envelope>";
		try {
			HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(g.control())
					.timeout(Duration.ofSeconds(3))
					.header("Content-Type", "text/xml; charset=\"utf-8\"")
					.header("SOAPAction", "\"" + g.service() + "#" + action + "\"")
					.POST(HttpRequest.BodyPublishers.ofString(body))
					.build(), HttpResponse.BodyHandlers.ofString());
			return r.statusCode() == 200 ? r.body() : "Fault " + r.statusCode();
		} catch (Exception e) {
			return null;
		}
	}

	/** Private / local IPv4 addresses (10/8, 172.16/12, 192.168/16, 100.64/10 carrier NAT, 127/8, 169.254/16). */
	static boolean isPrivate(String host) {
		String[] p = host.split("\\.");
		if (p.length != 4) {
			return false;
		}
		try {
			int a = Integer.parseInt(p[0]);
			int b = Integer.parseInt(p[1]);
			return a == 10 || a == 127 || (a == 172 && b >= 16 && b <= 31) || (a == 192 && b == 168)
					|| (a == 100 && b >= 64 && b <= 127) || (a == 169 && b == 254);
		} catch (NumberFormatException e) {
			return false;
		}
	}

	static String describe(InetSocketAddress a) {
		return a == null ? "none" : a.getAddress().getHostAddress() + ":" + a.getPort();
	}
}
