/*
  FastScreenCapture.java

  Copyright (C) 2020  Davide Perini

  Permission is hereby granted, free of charge, to any person obtaining a copy of
  this software and associated documentation files (the "Software"), to deal
  in the Software without restriction, including without limitation the rights
  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
  copies of the Software, and to permit persons to whom the Software is
  furnished to do so, subject to the following conditions:

  The above copyright notice and this permission notice shall be included in
  all copies or substantial portions of the Software.

  You should have received a copy of the MIT License along with this program.
  If not, see <https://opensource.org/licenses/MIT/>.
*/

package org.dpsoftware;

import com.sun.jna.Platform;
import com.sun.jna.platform.win32.Kernel32;
import gnu.io.CommPortIdentifier;
import gnu.io.PortInUseException;
import gnu.io.SerialPort;
import gnu.io.UnsupportedCommOperationException;
import lombok.Getter;
import org.freedesktop.gstreamer.Bin;
import org.freedesktop.gstreamer.Gst;
import org.freedesktop.gstreamer.Pipeline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Enumeration;
import java.util.concurrent.*;


/**
 * Fast Screen Capture for PC Ambilight
 * (https://github.com/sblantipodi/pc_ambilight)
 */
@Getter
public class FastScreenCapture {

    private static final Logger logger = LoggerFactory.getLogger(FastScreenCapture.class);

    // Number of CPU Threads to use, this app is heavy multithreaded,
    // high cpu cores equals to higher framerate but big CPU usage
    // 4 Threads are enough for 24FPS on an Intel i7 5930K@4.2GHz
    // 3 thread is enough for 30FPS with GPU Hardware Acceleration and uses nearly no CPU
    private int threadPoolNumber;
    private int executorNumber;
    // Calculate Screen Capture Framerate and how fast your microcontroller can consume it
    public static float FPS_CONSUMER;
    public static float FPS_PRODUCER;
    // Serial output stream
    private SerialPort serial;
    private OutputStream output;
    // LED strip, monitor and microcontroller config
    private Configuration config;
    // Start and Stop threads
    public static boolean RUNNING = false;
    // This queue orders elements FIFO. Producer offers some data, consumer throws data to the Serial port
    static BlockingQueue sharedQueue;
    // Image processing
    ImageProcessor imageProcessor;
    // Number of LEDs on the strip
    private int ledNumber;
    // GStreamer Rendering pipeline
    public static Pipeline pipe;
    public GUIManager tim;
    public static final String VERSION = "0.2.1";


    /**
     * Constructor
     */
    public FastScreenCapture() {

        loadConfigurationYaml();
        String ledMatrixInUse = config.getDefaultLedMatrix();
        sharedQueue = new LinkedBlockingQueue<Color[]>(config.getLedMatrixInUse(ledMatrixInUse).size()*30);
        imageProcessor = new ImageProcessor(config);
        ledNumber = config.getLedMatrixInUse(ledMatrixInUse).size();
        initSerial();
        initOutputStream();
        initThreadPool();

    }

    /**
     * Create one fast consumer and many producers.
     */
    public static void main(String[] args) throws Exception {

        UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        FastScreenCapture fscapture = new FastScreenCapture();

        ScheduledExecutorService scheduledExecutorService = Executors.newScheduledThreadPool(fscapture.threadPoolNumber);

        // Desktop Duplication API producers
        if (fscapture.config.getCaptureMethod() == Configuration.CaptureMethod.DDUPL) {
            fscapture.launchDDUPLGrabber(scheduledExecutorService, fscapture);
        } else { // Standard Producers
            fscapture.launchStandardGrabber(scheduledExecutorService, fscapture);
        }

        // Run a very fast consumer
        CompletableFuture.supplyAsync(() -> {
            try {
                fscapture.consume();
            } catch (InterruptedException | IOException e) {
                throw new RuntimeException(e);
            }
            return "Something went wrong.";
        }, scheduledExecutorService).thenAcceptAsync(s -> {
            logger.info(s);
        }).exceptionally(e -> {
            fscapture.clean();
            scheduledExecutorService.shutdownNow();
            Thread.currentThread().interrupt();
            return null;
        });

        // MQTT
        MQTTManager mqttManager = new MQTTManager(fscapture.config, fscapture);

        // Manage tray icon and framerate dialog
        fscapture.tim = new GUIManager(mqttManager);
        fscapture.tim.initTray(fscapture.config);
        fscapture.getFPS(fscapture.tim);

    }

    /**
     * Windows 8/10 Desktop Duplication API screen grabber (GStreamer)
     * @param scheduledExecutorService
     * @param fscapture main instance
     */
    void launchDDUPLGrabber(ScheduledExecutorService scheduledExecutorService, FastScreenCapture fscapture) {

        initGStreamerLibraryPaths();

        Gst.init("ScreenGrabber", "");
        scheduledExecutorService.scheduleAtFixedRate(() -> {
            if (RUNNING && FPS_PRODUCER == 0) {
                GStreamerGrabber vc = new GStreamerGrabber(fscapture.config);
                Bin bin = Gst.parseBinFromDescription(
                        "dxgiscreencapsrc ! videoconvert",true);
                pipe = new Pipeline();
                pipe.addMany(bin, vc.getElement());
                Pipeline.linkMany(bin, vc.getElement());
                JFrame f = new JFrame("ScreenGrabber");
                f.add(vc);
                vc.setPreferredSize(new Dimension(3840, 2160));
                f.pack();
                f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
                pipe.play();
                f.setVisible(false);
            }
        }, 1, 10, TimeUnit.SECONDS);

    }

    /**
     * Producers for CPU and WinAPI capturing
     * @param scheduledExecutorService
     * @param fscapture main instance
     * @throws AWTException
     */
    void launchStandardGrabber(ScheduledExecutorService scheduledExecutorService, FastScreenCapture fscapture) throws AWTException {

        Robot robot = null;

        for (int i = 0; i < fscapture.executorNumber; i++) {
            // One AWT Robot instance every 3 threads seems to be the sweet spot for performance/memory.
            if ((fscapture.config.getCaptureMethod() != Configuration.CaptureMethod.WinAPI) && i%3 == 0) {
                robot = new Robot();
                logger.info("Spawning new robot for capture");
            }
            Robot finalRobot = robot;
            // No need for completablefuture here, we wrote the queue with a producer and we forget it
            scheduledExecutorService.scheduleAtFixedRate(() -> {
                if (RUNNING) {
                    fscapture.producerTask(finalRobot);
                }
            }, 0, 25, TimeUnit.MILLISECONDS);
        }

    }

    /**
     * Load config yaml and create a default config if not present
     */
    void loadConfigurationYaml() {

        StorageManager sm = new StorageManager();
        config = sm.readConfig();

    }

    /**
     * Calculate Screen Capture Framerate and how fast your microcontroller can consume it
     */
    void getFPS(GUIManager tim) {

        ScheduledExecutorService scheduledExecutorService = Executors.newScheduledThreadPool(1);
        // Create a task that runs every 5 seconds
        Runnable framerateTask = () -> {
            if (FPS_PRODUCER > 0 || FPS_CONSUMER > 0) {
                float framerateProducer = FPS_PRODUCER / 5;
                float framerateConsumer = FPS_CONSUMER / 5;
                //logger.debug(" --* Producing @ " + framerateProducer + " FPS *-- "
                //    + " --* Consuming @ " + framerateConsumer + " FPS *-- ");
                tim.getJep().setText(tim.getInfoStr().replaceAll("FPS_PRODUCER",framerateProducer + "")
                        .replaceAll("FPS_CONSUMER",framerateConsumer + ""));
                FPS_CONSUMER = FPS_PRODUCER = 0;
            } else {
                tim.getJep().setText(tim.getInfoStr().replaceAll("FPS_PRODUCER",0 + "")
                        .replaceAll("FPS_CONSUMER",0 + ""));
            }
        };
        scheduledExecutorService.scheduleAtFixedRate(framerateTask, 0, 5, TimeUnit.SECONDS);

    }

    /**
     * Initialize Serial communication
     */
    private void initSerial() {

        CommPortIdentifier serialPortId = null;
        Enumeration enumComm = CommPortIdentifier.getPortIdentifiers();
        while (enumComm.hasMoreElements() && serialPortId == null) {
            CommPortIdentifier serialPortAvailable = (CommPortIdentifier) enumComm.nextElement();
            if (config.getSerialPort().equals(serialPortAvailable.getName()) || config.getSerialPort().equals("AUTO")) {
                serialPortId = serialPortAvailable;
            }
        }
        try {
            logger.info("Serial Port in use: " + serialPortId.getName());
            serial = (SerialPort) serialPortId.open(this.getClass().getName(), config.getTimeout());
            serial.setSerialPortParams(config.getDataRate(), SerialPort.DATABITS_8, SerialPort.STOPBITS_1, SerialPort.PARITY_NONE);
        } catch (PortInUseException | UnsupportedCommOperationException | NullPointerException e) {
            logger.error("Can't open SERIAL PORT");
            JOptionPane.showMessageDialog(null, "Can't open SERIAL PORT", "Fast Screen Capture", JOptionPane.PLAIN_MESSAGE);
            System.exit(0);
        }

    }

    /**
     * Initialize how many Threads to use in the ThreadPool and how many Executor to use
     */
    private void initThreadPool() {

        int numberOfCPUThreads = config.getNumberOfCPUThreads();
        threadPoolNumber = numberOfCPUThreads * 2;
        if (numberOfCPUThreads > 1) {
            if (config.getCaptureMethod() != Configuration.CaptureMethod.CPU) {
                executorNumber = numberOfCPUThreads;
            } else {
                executorNumber = numberOfCPUThreads * 3;
            }
        } else {
            executorNumber = numberOfCPUThreads;
        }

    }

    /**
     * Initialize OutputStream
     */
    private void initOutputStream() {

        try {
            output = serial.getOutputStream();
        } catch (IOException e) {
            e.printStackTrace();
        }

    }

    /**
     * Load GStreamer libraries
     */
    private void initGStreamerLibraryPaths() {

        String libPath = getInstallationPath() + "/gstreamer/1.0/x86_64/bin";

        if (libPath.isEmpty()) {
            return;
        }
        if (Platform.isWindows()) {
            try {
                Kernel32 k32 = Kernel32.INSTANCE;
                String path = System.getenv("path");
                if (path == null || path.trim().isEmpty()) {
                    k32.SetEnvironmentVariable("path", libPath);
                } else {
                    k32.SetEnvironmentVariable("path", libPath + File.pathSeparator + path);
                }
                return;
            } catch (Throwable e) {
                logger.error("Cant' find GStreamer");
            }
        }
        String jnaPath = System.getProperty("jna.library.path", "").trim();
        if (jnaPath.isEmpty()) {
            System.setProperty("jna.library.path", libPath);
        } else {
            System.setProperty("jna.library.path", jnaPath + File.pathSeparator + libPath);
        }

    }

    /**
     * Get the path where the users installed the software
     * @return String path
     */
    private String getInstallationPath() {

        String installationPath = FastScreenCapture.class.getProtectionDomain().getCodeSource().getLocation().toString();
        try {
            installationPath = installationPath.substring(6,
                installationPath.lastIndexOf("JavaFastScreenCapture-jar-with-dependencies.jar")) + "classes";
        } catch (StringIndexOutOfBoundsException e) {
            installationPath = installationPath.substring(6, installationPath.lastIndexOf("target"))
                + "src/main/resources";
        }
        logger.info("GStreamer path in use=" + installationPath.replaceAll("%20", " "));
        return installationPath.replaceAll("%20", " ");

    }

    /**
     * Write Serial Stream to the Serial Output
     * using Adalight Checksum
     * @param leds array of LEDs containing the average color to display on the LED
     */
    private void sendColors(Color[] leds) throws IOException {

        // Adalight checksum
        int ledsCountHi = ((ledNumber - 1) >> 8) & 0xff;
        int ledsCountLo = (ledNumber - 1) & 0xff;
        output.write('A');
        output.write('d');
        output.write('a');
        output.write(ledsCountHi);
        output.write(ledsCountLo);
        output.write((ledsCountHi ^ ledsCountLo ^ 0x55));

        for (int i = 0; i < ledNumber; i++) {
            output.write(leds[i].getRed()); //output.write(0);
            output.write(leds[i].getGreen()); //output.write(0);
            output.write(leds[i].getBlue()); //output.write(255);
        }
        FPS_CONSUMER++;

    }

    /**
     * Write Serial Stream to the Serial Output
     *
     * @param robot an AWT Robot instance for screen capture.
     *              One instance every three threads seems to be the hot spot for performance.
     */
    private void producerTask(Robot robot) {

        sharedQueue.offer(imageProcessor.getColors(robot, null));
        FPS_PRODUCER++;
        //System.gc(); // uncomment when hammering the JVM

    }

    /**
     * Print the average FPS number we are able to capture
     */
    int consume() throws InterruptedException, IOException {

        while (true) {
            Color[] num = (Color[]) sharedQueue.take();
            if (RUNNING) {
                if (num.length == ledNumber) {
                    sendColors(num);
                }
            }
        }

    }

    /**
     * Clean and Close Serial Output Stream
     */
    private void clean() {

        if(output != null) {
            try {
                output.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        if(serial != null) {
            serial.close();
        }

    }

}


