package ezcol.visual.visual2D;

import java.awt.*;
import java.awt.image.*;
import java.awt.event.*;
import java.io.*;

import ezcol.debug.Debugger;

import java.awt.datatransfer.*;
import ij.*;
import ij.gui.Roi;
import ij.gui.RoiListener;
import ij.gui.StackWindow;
import ij.gui.TrimmedButton;
import ij.process.*;
import ij.measure.*;
import ij.plugin.filter.Analyzer;

/** This class is a slightly modified version of HistogramWindow from ij.gui. **/
@SuppressWarnings("serial")
public class HistogramStackWindow extends StackWindow
		implements Measurements, ClipboardOwner, ImageListener, RoiListener, ActionListener, Runnable {

	static final int WIN_WIDTH = 300;
	static final int WIN_HEIGHT = 240;
	static final int HIST_WIDTH = 256;
	static final int HIST_HEIGHT = 128;
	static final int BAR_HEIGHT = 12;
	static final int XMARGIN = 20;
	static final int YMARGIN = 10;
	static final int INTENSITY = 0, RED = 1, GREEN = 2, BLUE = 3;

	protected ImageStatistics stats;
	protected long[] histogram;
	protected LookUpTable lut;
	protected Rectangle frame = null;
	protected Button list, save, copy, log, live, rgb;
	protected Label value, count;
	protected static String defaultDirectory = null;
	protected int decimalPlaces;
	protected int digits;
	protected long newMaxCount;
	protected int plotScale = 1;
	protected boolean logScale;
	protected Calibration cal;
	protected int yMax;
	public static int nBins = 256;

	private int srcImageID; // ID of source image
	private ImagePlus srcImp; // source image for live histograms
	private Thread bgThread; // thread background drawing
	private boolean doUpdate; // tells background thread to update
	private int channel; // RGB channel
	private String blankLabel;
	private boolean stackHistogram;

	// new fields
	protected ImageStatistics[] statsArray;
	protected long[][] histogramArray;
	protected ImageStack impStack;
	protected boolean[] logScaleArray;
	private int stackSize;
	private boolean withScrollBar;
	protected int[] numBins;
	private Button increase, decrease;
	private ImagePlus srcFloatImp;

	/** Displays a histogram using the title "Histogram of ImageName". */
	public HistogramStackWindow(ImagePlus imp) {
		super(createRGBImage("Histogram of " + imp.getShortTitle()));
		if (list == null)
			setup(imp);
		syncStack(imp);
		showHistogram(imp, 256, 0.0, 0.0);
		getCanvas().requestFocusInWindow();
		// updateImage(createRGBImage("Histogram of "+imp.getShortTitle(),
		// imp));
	}

	/**
	 * Displays a histogram using the specified title and number of bins.
	 * Currently, the number of bins must be 256 expect for 32 bit images.
	 */
	public HistogramStackWindow(String title, ImagePlus imp, int bins) {
		// super(createRGBImage(title, imp));
		super(createRGBImage(title));
		if (list == null)
			setup(imp);
		// this.imp = createRGBImage(title, imp);
		syncStack(imp);
		showHistogram(imp, bins, 0.0, 0.0);
		getCanvas().requestFocusInWindow();
		// updateImage(this.imp);
	}

	/**
	 * Displays a histogram using the specified title, number of bins and
	 * histogram range. Currently, the number of bins must be 256 and the
	 * histogram range range must be the same as the image range expect for 32
	 * bit images.
	 */
	public HistogramStackWindow(String title, ImagePlus imp, int bins, double histMin, double histMax) {
		super(createRGBImage(title));
		if (list == null)
			setup(imp);
		syncStack(imp);
		showHistogram(imp, bins, histMin, histMax);
		getCanvas().requestFocusInWindow();
	}

	/**
	 * Displays a histogram using the specified title, number of bins, histogram
	 * range and yMax.
	 */
	public HistogramStackWindow(String title, ImagePlus imp, int bins, double histMin, double histMax, int yMax) {
		super(createRGBImage(title));
		this.yMax = yMax;
		syncStack(imp);
		showHistogram(imp, bins, histMin, histMax);
		getCanvas().requestFocusInWindow();
	}

	/** Displays a histogram using the specified title and ImageStatistics. */
	/*
	 * public HistogramStackWindow(String title, ImagePlus imp, ImageStatistics
	 * stats) { super(NewImage.createRGBImage(title, WIN_WIDTH, WIN_HEIGHT, 1,
	 * NewImage.FILL_WHITE)); //IJ.log("HistogramWindow: "+stats.histMin+"  "
	 * +stats.histMax+"  "+stats.nBins); this.yMax = stats.histYMax;
	 * impStack=this.imp.getStack(); showHistogram(imp, stats); }
	 */

	private void syncStack(ImagePlus imp) {
		this.srcFloatImp = imp;
		this.impStack = createRGBImage(this.imp.getTitle(), imp).getStack();
		this.imp.setStack(null, impStack);

		stackSize = impStack.getSize();
		ImageStack impStack = imp.getStack();
		if (stackSize == 1) {
			String label = impStack.getSliceLabel(1);
			if (label != null)
				this.imp.setProperty("Label", label);
			withScrollBar = false;
		} else if (impStack.getSize() == stackSize) {
			for (int i = 1; i <= stackSize; i++)
				this.impStack.setSliceLabel(getShorterLabel(impStack.getSliceLabel(i), 10), i);
		}

	}

	private String getShorterLabel(String str, int len) {
		if (str == null)
			return "";
		else if (str.length() <= len)
			return str;
		else
			return str.substring(0, len);
	}

	private static ImagePlus createRGBImage(String title, ImagePlus imp) {
		ImagePlus rimp = null;
		rimp = IJ.createHyperStack(title, WIN_WIDTH, WIN_HEIGHT, imp.getNChannels(), imp.getNSlices(), imp.getNFrames(),
				24);
		rimp.resetStack();
		return rimp;
	}

	private static ImagePlus createRGBImage(String title) {
		ImagePlus rimp = null;
		rimp = IJ.createImage(title, WIN_WIDTH, WIN_HEIGHT, 1, 24);
		rimp.resetStack();
		return rimp;
	}

	/**
	 * Draws the histogram using the specified title and number of bins.
	 * Currently, the number of bins must be 256 expect for 32 bit images.
	 */
	public void showHistogram(ImagePlus imp, int bins) {
		showHistogram(imp, bins, 0.0, 0.0);
	}

	/**
	 * Draws the histogram using the specified title, number of bins and
	 * histogram range. Currently, the number of bins must be 256 and the
	 * histogram range range must be the same as the image range expect for 32
	 * bit images.
	 */
	public void showHistogram(ImagePlus imp, int bins, double histMin, double histMax) {
		// if the buttons are set up here, they will be below the slice bar
		// Hide the scrollbars first before adding buttons
		if (list == null) {
			// hideScrollbars();
			setup(imp);
			// showScrollbars();
		}
		boolean limitToThreshold = (Analyzer.getMeasurements() & LIMIT) != 0;
		ImageStack impStack = imp.getStack();
		statsArray = new ImageStatistics[impStack.getSize()];
		histogramArray = new long[impStack.getSize()][];
		logScaleArray = new boolean[impStack.getSize()];
		numBins = new int[impStack.getSize()];
		for (int iSlice = 1; iSlice <= impStack.getSize(); iSlice++) {
			if (channel != INTENSITY && imp.getType() == ImagePlus.COLOR_RGB) {
				ColorProcessor cp = (ColorProcessor) impStack.getProcessor(iSlice);
				ImageProcessor ip = cp.getChannel(channel, null);
				ImagePlus imp2 = new ImagePlus("", ip);
				imp2.setRoi(imp.getRoi());
				stats = imp2.getStatistics(AREA + MEAN + MODE + MIN_MAX, bins, histMin, histMax);
			} else {
				ImagePlus imp2 = new ImagePlus("", impStack.getProcessor(iSlice));
				stats = imp2.getStatistics(AREA + MEAN + MODE + MIN_MAX + (limitToThreshold ? LIMIT : 0), bins, histMin,
						histMax);
			}
			statsArray[iSlice - 1] = stats;
			numBins[iSlice - 1] = bins;
			showHistogram(imp, stats, iSlice);
		}
		this.imp.updateAndRepaintWindow();
	}

	/** Draws the histogram using the specified title and ImageStatistics. */
	public void showHistogram(ImagePlus imp, ImageStatistics stats, int iSlice) {
		ImageStack impStack = imp.getStack();
		stackHistogram = stats.stackStatistics;
		// if (list==null)
		// setup(imp);
		this.stats = stats;
		cal = imp.getCalibration();
		boolean limitToThreshold = (Analyzer.getMeasurements() & LIMIT) != 0;
		imp.getMask();
		histogram = stats.getHistogram();
		histogramArray[iSlice - 1] = histogram;
		if (limitToThreshold && histogram.length == 256) {
			ImageProcessor ip = impStack.getProcessor(iSlice);
			if (ip.getMinThreshold() != ImageProcessor.NO_THRESHOLD) {
				int lower = scaleDown(ip, ip.getMinThreshold());
				int upper = scaleDown(ip, ip.getMaxThreshold());
				for (int i = 0; i < lower; i++)
					histogram[i] = 0L;
				for (int i = upper + 1; i < 256; i++)
					histogram[i] = 0L;
			}
		}
		lut = imp.createLut();
		int type = imp.getType();
		boolean fixedRange = type == ImagePlus.GRAY8 || type == ImagePlus.COLOR_256 || type == ImagePlus.COLOR_RGB;
		ImageProcessor ip = this.impStack.getProcessor(iSlice);
		ip.setColor(Color.white);
		ip.resetRoi();
		ip.fill();
		drawHistogram(imp, iSlice, ip, fixedRange, stats.histMin, stats.histMax);
		// this.imp.updateAndDraw();
	}

	private void setup(ImagePlus imp) {
		boolean isRGB = imp.getType() == ImagePlus.COLOR_RGB;
		Panel buttons = new Panel();
		int hgap = IJ.isMacOSX() || isRGB ? 1 : 5;
		buttons.setLayout(new FlowLayout(FlowLayout.RIGHT, hgap, 0));
		int trim = IJ.isMacOSX() ? 6 : 0;
		list = new TrimmedButton("List", trim);
		list.addActionListener(this);
		buttons.add(list);
		copy = new TrimmedButton("Copy", trim);
		copy.addActionListener(this);
		buttons.add(copy);
		
		
		//Because inputs have negative values, taking log is not a good idea
		/** 
		 log = new TrimmedButton("Log", trim);
		 log.addActionListener(this);
		 buttons.add(log);
		**/

		// New buttons to increase and decrease the number of bins
		increase = new TrimmedButton("nBin+", trim);
		increase.addActionListener(this);
		buttons.add(increase);

		decrease = new TrimmedButton("nBin-", trim);
		decrease.addActionListener(this);
		buttons.add(decrease);

		// Now reason to keep live and rgb buttons
		/**
		 * if (!stackHistogram) { live = new TrimmedButton("Live", trim);
		 * live.addActionListener(this); buttons.add(live); } if (imp!=null &&
		 * isRGB && !stackHistogram) { rgb = new TrimmedButton("RGB", trim);
		 * rgb.addActionListener(this); buttons.add(rgb); }
		 **/

		if (!(IJ.isMacOSX() && isRGB)) {
			Panel valueAndCount = new Panel();
			valueAndCount.setLayout(new GridLayout(2, 1, 0, 0));
			blankLabel = IJ.isMacOSX() ? "           " : "                ";
			value = new Label(blankLabel);
			Font font = new Font("Monospaced", Font.PLAIN, 12);
			value.setFont(font);
			valueAndCount.add(value);
			count = new Label(blankLabel);
			count.setFont(font);
			valueAndCount.add(count);
			buttons.add(valueAndCount);
		}
		add(buttons);
		pack();
	}

	public void setup() {
		if (list == null)
			setup(imp);
	}

	public void mouseMoved(int x, int y) {

		if (value == null || count == null)
			return;
		if ((frame != null) && x >= frame.x && x <= (frame.x + frame.width)) {

			int iSlice = imp.getCurrentSlice();
			stats = statsArray[iSlice - 1];
			histogram = histogramArray[iSlice - 1];

			x = x - frame.x;
			if (x > 255)
				x = 255;
			int index = (int) (x * ((double) histogram.length) / HIST_WIDTH);
			String vlabel = null, clabel = null;
			if (blankLabel.length() == 11) // OS X
			{
				vlabel = " ";
				clabel = " ";
			} else {
				vlabel = " value=";
				clabel = " count=";
			}
			String v = vlabel + d2s(cal.getCValue(stats.histMin + index * stats.binSize)) + blankLabel;
			String c = clabel + histogram[index] + blankLabel;
			int len = vlabel.length() + blankLabel.length();
			value.setText(v.substring(0, len));
			count.setText(c.substring(0, len));
		} else {
			value.setText(blankLabel);
			count.setText(blankLabel);
		}
	}

	protected void drawHistogram(ImageProcessor ip, boolean fixedRange) {
		drawHistogram(null, 1, ip, fixedRange, 0.0, 0.0);
	}

	void drawHistogram(ImagePlus imp, int iSlice, ImageProcessor ip, boolean fixedRange, double xMin, double xMax) {
		int x, y;
		long maxCount2 = 0;
		@SuppressWarnings("unused")
		int mode2 = 0;
		long saveModalCount;

		ip.setColor(Color.black);
		ip.setLineWidth(1);
		decimalPlaces = Analyzer.getPrecision();
		digits = cal.calibrated() || stats.binSize != 1.0 ? decimalPlaces : 0;
		saveModalCount = histogram[stats.mode];
		for (int i = 0; i < histogram.length; i++) {
			if ((histogram[i] > maxCount2) && (i != stats.mode)) {
				maxCount2 = histogram[i];
				mode2 = i;
			}
		}
		newMaxCount = histogram[stats.mode];
		if ((newMaxCount > (maxCount2 * 2)) && (maxCount2 != 0)) {
			newMaxCount = (int) (maxCount2 * 1.5);
			// histogram[stats.mode] = newMaxCount;
		}
		if (logScale || IJ.shiftKeyDown() && !liveMode())
			drawLogPlot(yMax > 0 ? yMax : newMaxCount, ip);
		drawPlot(yMax > 0 ? yMax : newMaxCount, ip);
		histogram[stats.mode] = saveModalCount;
		x = XMARGIN + 1;
		y = YMARGIN + HIST_HEIGHT + 2;
		if (imp == null)
			lut.drawUnscaledColorBar(ip, x - 1, y, 256, BAR_HEIGHT);
		else
			drawAlignedColorBar(imp, iSlice, xMin, xMax, ip, x - 1, y, 256, BAR_HEIGHT);
		y += BAR_HEIGHT + 15;
		drawText(ip, x, y, fixedRange);
		srcImageID = imp.getID();
	}

	void drawAlignedColorBar(ImagePlus imp, int iSlice, double xMin, double xMax, ImageProcessor ip, int x, int y,
			int width, int height) {

		ImageProcessor ipSource = imp.getStack().getProcessor(iSlice);
		float[] pixels = null;
		ImageProcessor ipRamp = null;
		if (ipSource instanceof ColorProcessor) {
			ipRamp = new FloatProcessor(width, height);
			if (channel == RED)
				ipRamp.setColorModel(LUT.createLutFromColor(Color.red));
			else if (channel == GREEN)
				ipRamp.setColorModel(LUT.createLutFromColor(Color.green));
			else if (channel == BLUE)
				ipRamp.setColorModel(LUT.createLutFromColor(Color.blue));
			pixels = (float[]) ipRamp.getPixels();
		} else
			pixels = new float[width * height];
		for (int j = 0; j < height; j++) {
			for (int i = 0; i < width; i++)
				pixels[i + width * j] = (float) (xMin + i * (xMax - xMin) / (width - 1));
		}

		double min = stats != null ? stats.min : ipSource.getMin();
		double max = stats != null ? stats.max : ipSource.getMax();

		if (!(ipSource instanceof ColorProcessor)) {
			ColorModel cm = null;
			if (imp.isComposite()) {
				if (stats != null && stats.pixelCount > ipSource.getPixelCount()) { // stack
																					// histogram
					cm = LUT.createLutFromColor(Color.white);
					min = stats.min;
					max = stats.max;
				} else
					cm = ((CompositeImage) imp).getChannelLut();
			} else if (ipSource.getMinThreshold() == ImageProcessor.NO_THRESHOLD)
				cm = ipSource.getColorModel();
			else
				cm = ipSource.getCurrentColorModel();
			ipRamp = new FloatProcessor(width, height, pixels, cm);
		}

		ipRamp.setMinAndMax(min, max);
		ImageProcessor bar = null;
		if (ip instanceof ColorProcessor)
			bar = ipRamp.convertToRGB();
		else
			bar = ipRamp.convertToByte(true);
		ip.insert(bar, x, y);
		ip.setColor(Color.black);
		ip.drawRect(x - 1, y, width + 2, height);
	}

	/** Scales a threshold level to the range 0-255. */
	int scaleDown(ImageProcessor ip, double threshold) {
		double min = ip.getMin();
		double max = ip.getMax();
		if (max > min)
			return (int) (((threshold - min) / (max - min)) * 255.0);
		else
			return 0;
	}

	void drawPlot(long maxCount, ImageProcessor ip) {
		if (maxCount == 0)
			maxCount = 1;
		frame = new Rectangle(XMARGIN, YMARGIN, HIST_WIDTH, HIST_HEIGHT);
		ip.drawRect(frame.x - 1, frame.y, frame.width + 2, frame.height + 1);
		if (histogram.length <= HIST_WIDTH) {
			int index, y;
			for (int i = 0; i < HIST_WIDTH; i++) {
				index = (int) (i * (double) histogram.length / HIST_WIDTH);
				y = (int) (((double) HIST_HEIGHT * (double) histogram[index]) / maxCount);
				if (y > HIST_HEIGHT)
					y = HIST_HEIGHT;
				ip.drawLine(i + XMARGIN, YMARGIN + HIST_HEIGHT, i + XMARGIN, YMARGIN + HIST_HEIGHT - y);
			}
		} else {
			double xscale = (double) HIST_WIDTH / histogram.length;
			for (int i = 0; i < histogram.length; i++) {
				long value = histogram[i];
				if (value > 0L) {
					int y = (int) (((double) HIST_HEIGHT * (double) value) / maxCount);
					if (y > HIST_HEIGHT)
						y = HIST_HEIGHT;
					int x = (int) (i * xscale) + XMARGIN;
					ip.drawLine(x, YMARGIN + HIST_HEIGHT, x, YMARGIN + HIST_HEIGHT - y);
				}
			}
		}
	}

	void drawLogPlot(long maxCount, ImageProcessor ip) {
		frame = new Rectangle(XMARGIN, YMARGIN, HIST_WIDTH, HIST_HEIGHT);
		ip.drawRect(frame.x - 1, frame.y, frame.width + 2, frame.height + 1);
		double max = Math.log(maxCount);
		ip.setColor(Color.gray);
		if (histogram.length <= HIST_WIDTH) {
			int index, y;
			for (int i = 0; i < HIST_WIDTH; i++) {
				index = (int) (i * (double) histogram.length / HIST_WIDTH);
				y = histogram[index] == 0 ? 0 : (int) (HIST_HEIGHT * Math.log(histogram[index]) / max);
				if (y > HIST_HEIGHT)
					y = HIST_HEIGHT;
				ip.drawLine(i + XMARGIN, YMARGIN + HIST_HEIGHT, i + XMARGIN, YMARGIN + HIST_HEIGHT - y);
			}
		} else {
			double xscale = (double) HIST_WIDTH / histogram.length;
			for (int i = 0; i < histogram.length; i++) {
				long value = histogram[i];
				if (value > 0L) {
					int y = (int) (HIST_HEIGHT * Math.log(value) / max);
					if (y > HIST_HEIGHT)
						y = HIST_HEIGHT;
					int x = (int) (i * xscale) + XMARGIN;
					ip.drawLine(x, YMARGIN + HIST_HEIGHT, x, YMARGIN + HIST_HEIGHT - y);
				}
			}
		}
		ip.setColor(Color.black);
	}

	void drawText(ImageProcessor ip, int x, int y, boolean fixedRange) {
		ip.setFont(new Font("SansSerif", Font.PLAIN, 12));
		ip.setAntialiasedText(true);
		double hmin = cal.getCValue(stats.histMin);
		double hmax = cal.getCValue(stats.histMax);
		double range = hmax - hmin;
		if (fixedRange && !cal.calibrated() && hmin == 0 && hmax == 255)
			range = 256;
		ip.drawString(d2s(hmin), x - 4, y);
		ip.drawString(d2s(hmax), x + HIST_WIDTH - getWidth(hmax, ip) + 10, y);

		double binWidth = range / stats.nBins;
		binWidth = Math.abs(binWidth);
		boolean showBins = binWidth != 1.0 || !fixedRange;
		int col1 = XMARGIN + 5;
		int col2 = XMARGIN + HIST_WIDTH / 2;
		int row1 = y + 25;
		if (showBins)
			row1 -= 8;
		int row2 = row1 + 15;
		int row3 = row2 + 15;
		int row4 = row3 + 15;
		long count = stats.longPixelCount > 0 ? stats.longPixelCount : stats.pixelCount;
		String modeCount = " (" + stats.maxCount + ")";
		if (modeCount.length() > 12)
			modeCount = "";
		ip.drawString("Count: " + count, col1, row1);
		ip.drawString("Mean: " + d2s(stats.mean), col1, row2);
		ip.drawString("StdDev: " + d2s(stats.stdDev), col1, row3);
		ip.drawString("Mode: " + d2s(stats.dmode) + modeCount, col2, row3);
		ip.drawString("Min: " + d2s(stats.min), col2, row1);
		ip.drawString("Max: " + d2s(stats.max), col2, row2);

		if (showBins) {
			ip.drawString("Bins: " + d2s(stats.nBins), col1, row4);
			ip.drawString("Bin Width: " + d2s(binWidth), col2, row4);
		}
	}

	/*
	 * String d2s(double d) { if (d==Double.MAX_VALUE||d==-Double.MAX_VALUE)
	 * return "0"; else if (Double.isNaN(d)) return("NaN"); else if
	 * (Double.isInfinite(d)) return("Infinity"); else if ((int)d==d) return
	 * ResultsTable.d2s(d,0); else return ResultsTable.d2s(d,decimalPlaces); }
	 */

	private String d2s(double d) {
		if ((int) d == d)
			return IJ.d2s(d, 0);
		else
			return IJ.d2s(d, 3, 8);
	}

	int getWidth(double d, ImageProcessor ip) {
		return ip.getStringWidth(d2s(d));
	}

	/** Returns the histogram values as a ResultsTable. */
	public ResultsTable getResultsTable() {
		ResultsTable rt = new ResultsTable();
		rt.showRowNumbers(false);
		String vheading = stats.binSize == 1.0 ? "value" : "bin start";
		if (cal.calibrated() && !cal.isSigned16Bit()) {
			for (int i = 0; i < stats.nBins; i++) {
				rt.setValue("level", i, i);
				rt.setValue(vheading, i, cal.getCValue(stats.histMin + i * stats.binSize));
				rt.setValue("count", i, histogram[i]);
			}
			rt.setDecimalPlaces(0, 0);
			rt.setDecimalPlaces(1, digits);
			rt.setDecimalPlaces(2, 0);
		} else {
			for (int i = 0; i < stats.nBins; i++) {
				rt.setValue(vheading, i, cal.getCValue(stats.histMin + i * stats.binSize));
				rt.setValue("count", i, histogram[i]);
			}
			rt.setDecimalPlaces(0, digits);
			rt.setDecimalPlaces(1, 0);
		}
		return rt;
	}

	protected void showList() {
		ResultsTable rt = getResultsTable();
		rt.show(getTitle());
	}

	protected void copyToClipboard() {
		Clipboard systemClipboard = null;
		try {
			systemClipboard = getToolkit().getSystemClipboard();
		} catch (Exception e) {
			systemClipboard = null;
		}
		if (systemClipboard == null) {
			IJ.error("Unable to copy to Clipboard.");
			return;
		}
		IJ.showStatus("Copying histogram values...");
		CharArrayWriter aw = new CharArrayWriter(stats.nBins * 4);
		PrintWriter pw = new PrintWriter(aw);
		for (int i = 0; i < stats.nBins; i++)
			pw.print(ResultsTable.d2s(cal.getCValue(stats.histMin + i * stats.binSize), digits) + "\t" + histogram[i]
					+ "\n");
		String text = aw.toString();
		pw.close();
		StringSelection contents = new StringSelection(text);
		systemClipboard.setContents(contents, this);
		IJ.showStatus(text.length() + " characters copied to Clipboard");
	}

	void replot() {
		ImageProcessor ip = this.imp.getProcessor();
		frame = new Rectangle(XMARGIN, YMARGIN, HIST_WIDTH, HIST_HEIGHT);
		ip.setColor(Color.white);
		ip.setRoi(frame.x - 1, frame.y, frame.width + 2, frame.height);
		ip.fill();
		ip.resetRoi();
		ip.setColor(Color.black);
		if (logScale) {
			drawLogPlot(yMax > 0 ? yMax : newMaxCount, ip);
			drawPlot(yMax > 0 ? yMax : newMaxCount, ip);
		} else
			drawPlot(yMax > 0 ? yMax : newMaxCount, ip);
		this.imp.updateAndDraw();
	}
	
	/**
	 * Redraw current histogram after the number of bins has been changed
	 */
	void replotHistogram(int iSlice){
		
		boolean limitToThreshold = (Analyzer.getMeasurements() & LIMIT) != 0;
		ImagePlus imp = this.srcFloatImp;
		ImageStack impStack = imp.getStack();
		double histMin = stats.histMin;
		double histMax = stats.histMax;
		int bins = numBins[iSlice - 1];
		
		if (channel != INTENSITY && imp.getType() == ImagePlus.COLOR_RGB) {
			ColorProcessor cp = (ColorProcessor) impStack.getProcessor(iSlice);
			ImageProcessor ip = cp.getChannel(channel, null);
			ImagePlus imp2 = new ImagePlus("", ip);
			imp2.setRoi(imp.getRoi());
			stats = imp2.getStatistics(AREA + MEAN + MODE + MIN_MAX, bins, histMin, histMax);
		} else {
			ImagePlus imp2 = new ImagePlus("", impStack.getProcessor(iSlice));
			stats = imp2.getStatistics(AREA + MEAN + MODE + MIN_MAX + (limitToThreshold ? LIMIT : 0), bins, histMin,
					histMax);
		}
		statsArray[iSlice - 1] = stats;
		numBins[iSlice - 1] = bins;
		showHistogram(imp, stats, iSlice);
		this.imp.updateAndRepaintWindow();
	}

	/*
	 * void rescale() { Graphics g = img.getGraphics(); plotScale *= 2; if
	 * ((newMaxCount/plotScale)<50) { plotScale = 1; frame = new
	 * Rectangle(XMARGIN, YMARGIN, HIST_WIDTH, HIST_HEIGHT);
	 * g.setColor(Color.white); g.fillRect(frame.x, frame.y, frame.width,
	 * frame.height); g.setColor(Color.black); } drawPlot(newMaxCount/plotScale,
	 * g); //ImageProcessor ip = new ColorProcessor(img);
	 * //this.imp.setProcessor(null, ip); this.imp.setImage(img); }
	 */

	public void actionPerformed(ActionEvent e) {
		super.actionPerformed(e);
		int iSlice = imp.getCurrentSlice();
		stats = statsArray[iSlice - 1];
		histogram = histogramArray[iSlice - 1];
		logScale = logScaleArray[iSlice - 1];
		Object b = e.getSource();
		if (b == live)
			toggleLiveMode();
		else if (b == rgb)
			changeChannel();
		else if (b == list)
			showList();
		else if (b == copy)
			copyToClipboard();
		else if (b == log) {
			logScale = !logScale;
			logScaleArray[iSlice - 1] = logScale;
			replot();
		} else if (b == increase) {
			numBins[iSlice - 1]++;
			replotHistogram(iSlice);
		} else if (b == decrease) {
			if(numBins[iSlice - 1] > 1){
				numBins[iSlice - 1]--;
				replotHistogram(iSlice);
			}
		}
	}

	public void lostOwnership(Clipboard clipboard, Transferable contents) {
	}

	public int[] getHistogram() {
		int[] hist = new int[histogram.length];
		for (int i = 0; i < histogram.length; i++)
			hist[i] = (int) histogram[i];
		return hist;
	}

	public double[] getXValues() {
		double[] values = new double[stats.nBins];
		for (int i = 0; i < stats.nBins; i++)
			values[i] = cal.getCValue(stats.histMin + i * stats.binSize);
		return values;
	}

	private void toggleLiveMode() {
		if (liveMode())
			removeListeners();
		else
			enableLiveMode();
	}

	private void changeChannel() {
		ImagePlus imp = WindowManager.getImage(srcImageID);
		if (imp == null || imp.getType() != ImagePlus.COLOR_RGB) {
			channel = INTENSITY;
			return;
		} else {
			channel++;
			if (channel > BLUE)
				channel = INTENSITY;
			showHistogram(imp, 256);
			String name = this.imp.getTitle();
			if (name.startsWith("Red "))
				name = name.substring(4);
			else if (name.startsWith("Green "))
				name = name.substring(6);
			else if (name.startsWith("Blue "))
				name = name.substring(5);
			switch (channel) {
			case INTENSITY:
				this.imp.setTitle(name);
				break;
			case RED:
				this.imp.setTitle("Red " + name);
				break;
			case GREEN:
				this.imp.setTitle("Green " + name);
				break;
			case BLUE:
				this.imp.setTitle("Blue " + name);
				break;
			}
		}
	}

	private boolean liveMode() {
		return live != null && live.getForeground() == Color.red;
	}

	private void enableLiveMode() {
		if (bgThread == null) {
			srcImp = WindowManager.getImage(srcImageID);
			if (srcImp == null)
				return;
			bgThread = new Thread(this, "Live Histogram");
			bgThread.setPriority(Math.max(bgThread.getPriority() - 3, Thread.MIN_PRIORITY));
			bgThread.start();
			imageUpdated(srcImp);
		}
		createListeners();
		if (srcImp != null)
			imageUpdated(srcImp);
	}

	// Unused
	public void imageOpened(ImagePlus imp) {
	}

	// This listener is called if the source image content is changed
	public synchronized void imageUpdated(ImagePlus imp) {
		if (imp == srcImp) {
			doUpdate = true;
			notify();
		}
	}

	public synchronized void roiModified(ImagePlus img, int id) {
		if (img == srcImp) {
			doUpdate = true;
			notify();
		}
	}

	// If either the source image or this image are closed, exit
	public void imageClosed(ImagePlus imp) {
		if (imp == srcImp || imp == this.imp) {
			if (bgThread != null)
				bgThread.interrupt();
			bgThread = null;
			removeListeners();
			srcImp = null;
		}
	}

	// the background thread for live plotting.
	@Override
	public void run() {
		while (!done) {
			
			synchronized (this) {
				try {
					wait();
				} // notify wakes up the thread
				catch (InterruptedException e) { 
				}
			}
			if (done)
				return;
			if (doUpdate && srcImp != null && Thread.currentThread() == bgThread) {
				if (srcImp.getRoi() != null)
					IJ.wait(50); // delay to make sure the roi has been updated
				if (srcImp != null) {
					if (srcImp.getNChannels() != imp.getNChannels() || srcImp.getNFrames() != imp.getNFrames()
							|| srcImp.getNSlices() != imp.getNSlices()) {
						setImage(createRGBImage(imp.getShortTitle(), srcImp));
						updateSliceSelector();
					}
					syncStack(srcImp);
					updateScrollBars();
					if (srcImp.getBitDepth() == 16 && ImagePlus.getDefault16bitRange() != 0)
						showHistogram(srcImp, 256, 0, Math.pow(2, ImagePlus.getDefault16bitRange()) - 1);
					else
						showHistogram(srcImp, 256);

					repaint();
					int slice = srcImp.getCurrentSlice();
					if (slice > 0 && slice <= imp.getStackSize() && slice != imp.getCurrentSlice())
						imp.setSlice(slice);
				}
				doUpdate = false;
			}
			//if (done)
			//	return;
			if (Thread.currentThread() == thread && slice > 0) {
				int s = slice;
				slice = 0;
				if (imp != null && s != imp.getCurrentSlice())
					imp.setSlice(s);
			}

		}
	}

	private void createListeners() {
		if (srcImp == null)
			return;
		ImagePlus.addImageListener(this);
		Roi.addRoiListener(this);
		if (live != null) {
			Font font = live.getFont();
			live.setFont(new Font(font.getName(), Font.BOLD, font.getSize()));
			live.setForeground(Color.red);
		}
	}

	private void removeListeners() {
		if (srcImp == null)
			return;
		ImagePlus.removeImageListener(this);
		Roi.removeRoiListener(this);
		if (live != null) {
			Font font = live.getFont();
			live.setFont(new Font(font.getName(), Font.PLAIN, font.getSize()));
			live.setForeground(Color.black);
		}
	}

	private void updateScrollBars() {
		int stackSize = imp.getStackSize();
		if (stackSize == 1 && !withScrollBar) {
			hideScrollbars();
			withScrollBar = true;
		} else if (stackSize > 1 && withScrollBar)
			showScrollbars();
	}

	protected void hideScrollbars() {
		if (cSelector != null) {
			cSelector.setVisible(false);
			cSelector.removeAdjustmentListener(this);
		}
		if (zSelector != null) {
			zSelector.setVisible(false);
			zSelector.removeAdjustmentListener(this);
		}
		if (tSelector != null) {
			tSelector.setVisible(false);
			tSelector.removeAdjustmentListener(this);
		}
		// pack();
	}

	protected void showScrollbars() {
		if (cSelector != null) {
			cSelector.setVisible(true);
			cSelector.addAdjustmentListener(this);
		}
		if (zSelector != null) {
			zSelector.setVisible(true);
			zSelector.addAdjustmentListener(this);
		}
		if (tSelector != null) {
			tSelector.setVisible(true);
			tSelector.addAdjustmentListener(this);
		}
		// pack();
	}

}
