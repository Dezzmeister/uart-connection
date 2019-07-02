package main;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Enumeration;
import java.util.Scanner;

import purejavacomm.CommPortIdentifier;
import purejavacomm.PortInUseException;
import purejavacomm.SerialPort;
import purejavacomm.SerialPortEvent;
import purejavacomm.UnsupportedCommOperationException;

public class Main {
	
	private static String portName;
	private static int baudRate;
	private static SerialPort port;
	private static InputStream dataIn;
	private static PrintStream dataOut;
	private static Thread senderThread;
	
	private static volatile boolean communicating = true;
	
	private static volatile boolean sendingFile = false;
	private static String fileName = "";
	
	private static Scanner userInput = new Scanner(System.in);
	
	private static final byte SENDFILE_OPCODE = 0b00000001;
	
	public static void main(final String[] args) {
		parseArgs(args);
		setupSerialPort();
		waitForStopCode();
	}
	
	private static void sendData() {
		while (communicating) {			
			if (sendingFile) {
				try {
					byte[] file = Files.readAllBytes(Paths.get(fileName));
					int fileSize = file.length;
					
					byte[] data = new byte[fileSize + 5];
					data[0] = SENDFILE_OPCODE;
					data[1] = (byte)((fileSize & 0xFF000000) >>> 24);
					data[2] = (byte)((fileSize & 0x00FF0000) >>> 16);
					data[3] = (byte)((fileSize & 0x0000FF00) >>> 8);
					data[4] = (byte)(fileSize & 0x000000FF);
					
					for (int i = 0; i < file.length; i++) {
						data[i + 5] = file[i];
					}
					
					dataOut.write(data);
					sendingFile = false;
				} catch (Exception e) {
					e.printStackTrace();
					System.err.println("Error sending file \"" + fileName + "\"!");
					sendingFile = false;
				}
			}
		}
	}
	
	private static void waitForStopCode() {
		while (communicating) {
			String nextLine = userInput.nextLine();
			String[] args = nextLine.split(" ");
			if (args[0].equals("sendfile")) {
				if (args.length >= 2) {
					fileName = args[1];
					sendingFile = true;
				} else {
					System.err.println("You need to supply a file path to the 'sendfile' command! (No spaces)");
				}
			} else {
				System.err.println("Not a valid command!");
			}
		}
		
		try {
			dataIn.close();
			dataOut.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
		port.close();
	}
	
	private static void dataAvailable(final SerialPortEvent event) {
		int in;
		try {
			while ((in = dataIn.read()) != -1) {
				System.out.print(Integer.toHexString(in));
			}
		} catch (IOException e) {
			e.printStackTrace();
			System.err.println("Error occurred reading data from the device, or the device was disconnected\nStopping...");
			System.exit(-1);
		}
	}
	
	private static void handleSerialEvent(final SerialPortEvent event) {
		switch (event.getEventType()) {
			case SerialPortEvent.DATA_AVAILABLE:
				dataAvailable(event);
				break;
		}
	}
	
	/**
	 * Sets up the serial port with 8 data bits, 1 stop bit, and no parity bits.
	 */
	private static void setupSerialPort() {
		Enumeration<CommPortIdentifier> portIdentifiers = CommPortIdentifier.getPortIdentifiers();
		CommPortIdentifier portId = null;
		
		while (portIdentifiers.hasMoreElements()) {
			CommPortIdentifier pid = portIdentifiers.nextElement();
			if (pid.getPortType() == CommPortIdentifier.PORT_SERIAL && pid.getName().equals(portName)) {
				portId = pid;
				System.out.println("Found serial port " + portName);
				break;
			}
		}
		
		if (portId == null) {
			System.err.println("Failed to find serial port " + portName + "\nStopping...");
			System.exit(-1);
		}
		
		port = null;
		try {
			port = (SerialPort) portId.open("UART-RAM", 8000);
		} catch (PortInUseException e) {
			e.printStackTrace();
			System.err.println("Port \"" + portName + "\" in use\nStopping...");
			System.exit(-1);
		}
		
		try {
			port.setSerialPortParams(baudRate, SerialPort.DATABITS_8, SerialPort.STOPBITS_1, SerialPort.PARITY_NONE);
		} catch (UnsupportedCommOperationException e) {
			e.printStackTrace();
			System.err.println("Problem setting the serial port parameters\nStopping...");
			System.exit(-1);
		}
		port.notifyOnDataAvailable(true);
		port.notifyOnOutputEmpty(true);
		
		try {
			dataIn = port.getInputStream();
			port.addEventListener(Main::handleSerialEvent);
		} catch (Exception e) {
			e.printStackTrace();
			System.err.println("Problem getting the InputStream and adding an event listener\nStopping...");
			System.exit(-1);
		}
		
		try {
			dataOut = new PrintStream(port.getOutputStream(), true);
		} catch (Exception e) {
			e.printStackTrace();
			System.err.println("problem getting the OutputStream\nStopping...");
			System.exit(-1);
		}
		
		senderThread = new Thread(Main::sendData, "UART-CONNECTION sender thread");
		senderThread.start();
	}
	
	private static void parseArgs(final String[] args) {
		if (args.length < 2) {
			System.err.println("You need two arguments: the port name, and the baud rate!");
			System.exit(-1);
		} else {
			portName = args[0];
			baudRate = Integer.parseInt(args[1]);
		}
	}
}
