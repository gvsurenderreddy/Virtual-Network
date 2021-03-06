package router;

import headers.ICMP_Header;
import headers.IP_Header;
import headers.UDP_Header;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Scanner;

/********************************************************************
 * Router.java
 *
 *
 * @author Jack O'Brien
 * @author Megan Maher
 * @author Tyler McCarthy
 * @version Oct 25, 2014
 *******************************************************************/
public class Router {
	
	private final int PORT = 1618;
	
	/** Path to the configuration files. */
	private final String PATH = "config/";
	
	private int router_number;
	
	/** HashMap where the Key is a prefix in the format: IPv4/prefix_length.
	 * The value is the "real" IPv4 address associated with the prefix. */
	private HashMap<String, InetAddress> prefixes;
	
	private ArrayList<InetAddress> routerAddresses;
	
	private DatagramSocket routerSocket;
	
	/****************************************************************
	 * Constructor for Router. Creates the router socket
	 * 
	 * @throws SocketException if the port is in use
	 ***************************************************************/
	public Router(int router_number) throws SocketException, Exception {
		routerSocket = new DatagramSocket(PORT);
		
		prefixes = new HashMap<String, InetAddress>();
		routerAddresses = new ArrayList<InetAddress>();
		
		setRouterNumber(router_number);
		readAddresses();
		
		printWelcomeMessage();
	}
	
	private void printWelcomeMessage() {
		try {
			System.out.println("-- Router started on " + 
					InetAddress.getLocalHost().getHostAddress() + 
					":" + PORT + " --\n");
		} catch (UnknownHostException e) {}
		
		String msg = String.format("%15s   %-15s", "-Prefixes-","-Address-");
		System.out.println(msg);
		
		for (String prefix : prefixes.keySet()) {
			String dst = prefixes.get(prefix).getHostAddress();
			msg = String.format("%14s -> %-15s", prefix, dst);
			System.out.println(msg);
		}
		
	}
	
	/****************************************************************
	 * Sets the router number for this router and then reads the 
	 * file which corresponds to the given number,
	 * 
	 * @param router_number the number of this router
	 * @throws Exception if there is something wrong with the 
	 * configuration file for the given router number
	 ***************************************************************/
	private void setRouterNumber(int router_number) throws Exception {
		this.router_number = router_number;
		readPrefixes();
	}
	
	private DatagramPacket receivePacket() throws IOException {
		byte[] buffer = new byte[65535];
		
		DatagramPacket recvPacket = new DatagramPacket(buffer, buffer.length);
		routerSocket.receive(recvPacket);
				
		return recvPacket;
	}
	
	private void handlePacket() throws IOException {
		DatagramPacket recvPacket = receivePacket();
		byte[] data = recvPacket.getData();
				
		String src = translateIP(data, 12);
		
		System.out.println("\nReceived packet from " + src);
		
		int data_length = (int) (((data[2] << 8) | (data[3] & 0xFF)) & 0xFFFF);

		// Validates the checksums
		validateChecksumIP(data, 0, 20);
		
		int protocol = (int) (data[9] & 0xFF);
		final int UDP = 17;
		final int ICMP = 1;
		
		if (protocol == UDP) {
			System.out.println("IPv4 Protocol 17: UDP");
			validateChecksumUDP(data, 20, data_length);
		} else if (protocol == ICMP) {
			System.out.println("IPv4 Protocol 1: ICMP");
			validateChecksumICMP(data, 20, 56);
		}
		
		String dest = translateIP(data, 16);
		
		/* Checks if the destination is this router */
		for (InetAddress addr : routerAddresses) {

			if (addr.getHostAddress().equals(dest)) {
				printMessage(data, data_length, src);
				return;
			}
		}
		
		InetAddress realDstIP = findMatch(dest);
		
		/* If no prefix match was found, the sender must be notified. */
		if (realDstIP == null) {
			System.out.println("No prefix match for: " + dest);
			sendICMP(ICMP_Header.UNREACHABLE, data, src);
			return;
		}
		
		System.out.println("Found prexif for " + dest + " -> " + 
				realDstIP.getHostAddress());
		
		int TTL = (int) (data[8] & 0xFF);
		
		/* Check if the TTL has expired*/
		if (TTL <= 0) { 
			System.out.println("TTL Expired");
			sendICMP(ICMP_Header.TIME_EXCEEDED, data, src);
			return;
		}
		
		// Decrements the TTL
		TTL--;
		data[8] = (byte) TTL;		
		
		// Updates the checksum
		int newChecksum = IP_Header.calculateChecksum(data, 0, 20);
		data[10] = (byte) (newChecksum >>> 8);
		data[11] = (byte) (newChecksum);
		
		DatagramPacket sendPkt = new DatagramPacket(data, data_length, 
				realDstIP, PORT);
		
		/* Forwards the packet */
		routerSocket.send(sendPkt);
		
		System.out.println("Sent message to " + realDstIP.getHostAddress());
	}	
	
	private InetAddress findMatch(String dest) {
		InetAddress realDstIP = null;
		int prefixLength = 0;
		
		/* Finds the best matching prefix for the destination IP */
		for (String prefix : prefixes.keySet()) {
			
			String[] prefixArr = prefix.split("\\.");
			String[] destArr = dest.split("\\.");
			
			String p = "";
			dest = "";
			
			for (int i = 0; i < prefixArr.length; i++) {
				p += String.format("%3s", prefixArr[i]).replace(' ', '0');
			}
			
			for (int i = 0; i < destArr.length; i++) {
				dest += String.format("%3s", destArr[i]).replace(' ', '0');
			}
			
			if (dest.startsWith(p) && p.length() > prefixLength) {
				realDstIP = prefixes.get(prefix);
				prefixLength = prefix.length();
			}
		}
		
		return realDstIP;
	}
	
	private void printMessage(byte[] data, int end, String srcIP) {
		System.out.print("Message: ");
		
		String str = "";
		
		for (int i = 28; i < end; i++) {
			str += (char) (data[i] & 0xFF);
		}
		
		System.out.println(str);
	}

	private void validateChecksumIP(byte[] data, int start, int end) 
			throws IOException {
		System.out.print("Checking IP Checksum... ");
		
		int checksumIndex = start + 10;
		
		int storedChecksum = (int) (((data[checksumIndex] << 8) | 
							 (data[checksumIndex + 1] & 0xFF)) & 0xFFFF);
		int calculatedChecksum = IP_Header.calculateChecksum(data, start, end);
		
		if (storedChecksum != calculatedChecksum) {
			throw new IOException("Bad IP Checksum: Got " + storedChecksum + 
					" Expected " + calculatedChecksum);
		}
		
		System.out.println("Good");
	}
	
	private void validateChecksumUDP(byte[] data, int start, int end) 
			throws IOException {
		System.out.print("Checking UDP Checksum... ");
		
		int checksumIndex = start + 6;
		
		int storedChecksum = (int) (((data[checksumIndex] << 8) | 
							 (data[checksumIndex + 1] & 0xFF)) & 0xFFFF);
		int calculatedChecksum = UDP_Header.calculateChecksum(data, start, end);
		
		if (storedChecksum != calculatedChecksum) {
			throw new IOException("Bad UDP Checksum: Got " + storedChecksum + 
					" Expected " + calculatedChecksum);
		}
		
		System.out.println("Good");
	}
	
	private void validateChecksumICMP(byte[] data, int start, int end) 
			throws IOException {
		System.out.print("Checking ICMP Checksum... ");
				
		int checksumIndex = start + 2;
		
		int storedChecksum = (int) (((data[checksumIndex] << 8) | 
							 (data[checksumIndex + 1] & 0xFF)) & 0xFFFF);
		int calculatedChecksum = 
				ICMP_Header.calculateChecksum(data, start, end);
		
		if (storedChecksum != calculatedChecksum) {
			throw new IOException("Bad ICMP Checksum: Got " + storedChecksum + 
					" Expected " + calculatedChecksum);
		}
		
		System.out.println("Good");
	}
	
	/****************************************************************
	 * Reads the configuration file based on the router_number.
	 * 
	 * @throws Exception if there is any issue with the configuration
	 * file. (not found, improperly formatted, etc.)
	 ***************************************************************/
	private void readPrefixes() throws Exception {
		String path = PATH + "router-" + router_number + ".txt";
				
		BufferedReader br = new BufferedReader(new FileReader(path));
		String line;
		
		/* Reads the file line-by-line */
		while ((line = br.readLine()) != null) {
			
			/* Skips lines that don't start "prefix " */
			if (!line.startsWith("prefix ")) continue;
			
			String[] splitStr = line.split(" ");

			/* Skips improperly formated lines */
			if (splitStr.length != 3) continue;
			
			String key = splitStr[1];
			String[] keySplit = key.split("/");
			
			/* Skips improperly formated prefixes */
			if (keySplit.length != 2) continue;
			
			int prefixLength = Integer.parseInt(keySplit[1]);
			prefixLength /= 8; // in bytes
			
			String[] ipSplit = keySplit[0].split("\\.");
			
			/* Skips improperly formatted IPv4 addresses in the prefix */
			if (ipSplit.length != 4) continue;
			key = "";
			
			/* Rebuilds prefix with only the relevant bytes. 
			 * Example: "10.2.0.0/16" -> "10.2" */
			for (int i = 0; i < prefixLength; i++) {
				key += "." + ipSplit[i];
			}
			
			InetAddress value = null;			
			
			/* Skips invalid IPv4 addresses */
			try {
				value = InetAddress.getByName(splitStr[2]);
			} catch (UnknownHostException e) {
				continue;
			}
			
			prefixes.put(key.substring(1), value);
		}
		
		br.close();
	}
	
	private void sendICMP(int type, byte[] data, String src) 
			throws IOException {
		byte[] ipUDP = new byte[28];
		
		for (int i = 0; i < ipUDP.length; i++) {
			ipUDP[i] = data[i];
		}
		
		InetAddress sender = findMatch(src); 
		
		InetAddress virtSrc = InetAddress.getByName(src);
		
		IP_Header ipHeader = new IP_Header(1);
		ipHeader.setDestination(virtSrc);
		ipHeader.setSource(routerAddresses.get(0));
		ipHeader.setDataSize(36);
		String ipBits = ipHeader.getBitString();
		
		byte[] ipBytes = new byte[20];
		
		/* Converts the bit strings into bytes and adds them
		 * to the byte array. */
		for (int i = 0; i < ipBytes.length; i++) {
			int start = i * 8;
			int end = start + 8;
			
			String octet = ipBits.substring(start, end);
			
			ipBytes[i] = (byte) Integer.parseInt(octet, 2);
		}
		
		ICMP_Header icmp = new ICMP_Header(type);
		byte[] icmpBytes = icmp.getBytes(ipUDP);
		icmpBytes = icmp.getBytes(ipUDP); // Need this twice!
				
		byte[] packetBytes = new byte[ipBytes.length + icmpBytes.length];
		
		for (int i = 0; i < ipBytes.length; i++) {
			packetBytes[i] = ipBytes[i];
		}
		
		for (int i = 0; i < icmpBytes.length; i++) {
			packetBytes[i + ipBytes.length] = icmpBytes[i];
		}
		
		DatagramPacket sendPkt = new DatagramPacket(packetBytes, 
				packetBytes.length, sender, PORT);
		
		routerSocket.send(sendPkt);
		
		System.out.println("Sent ICMP type " + type + " to " + src);
	}
	
	private void readAddresses() throws Exception {
		String path = PATH + "router-" + router_number + ".txt";
		
		BufferedReader br = new BufferedReader(new FileReader(path));
		String line;
		
		/* Reads the file line-by-line */
		while ((line = br.readLine()) != null) {
			
			if (!line.startsWith("address ")) continue;
			
			String[] strArr = line.split(" ");
			InetAddress addr = null;
			
			try {
				addr = InetAddress.getByName(strArr[1]);
			} catch (UnknownHostException e) {
				continue;
			}
			
			routerAddresses.add(addr);
		}
		
		br.close();
	}
	
	/****************************************************************
	 * Converts the 4 bytes from the given starting index into
	 * IPv4 format as a String.
	 * 
	 * @param data the byte array containing the IPv4 address.
	 * @param start the index of the first byte of the address.
	 * @return String representation of the address.
	 ***************************************************************/
	private String translateIP(byte[] data, int start) {
		String ip = "";
		
		for (int i = 0; i < 4; i++) {
			ip += "." + Integer.toString((int) (data[i + start] & 0xFF));
		}
		
		return ip.substring(1);
	}
	
	public void begin() {
		while(true) {
			try {
				handlePacket();
			} catch (IOException e) {
				System.err.println(e.getMessage());
				continue;
			}
		}
	}
	
	public static void main(String[] args) {
		
		System.out.print("Please input router number: ");
		
		int router_number = 0;
		
		Scanner scan = new Scanner(System.in);
		router_number = scan.nextInt();
		scan.close();
		
		System.out.println();
		
		Router router = null;
		
		try {
			router = new Router(router_number);
		} catch (SocketException se) {
			String message = "Port in use";
			System.err.println(message);
		} catch (FileNotFoundException fnfe) {
			String message = "File: router-" + router_number + 
					".txt not found";
			System.err.println(message);
		} catch (Exception e) {
			e.printStackTrace(); //TODO: Remove this
			String message = "Invalid configuration file";
			System.err.println(message);
		}
		
		if (router != null) 
			router.begin();
		
	}
}
