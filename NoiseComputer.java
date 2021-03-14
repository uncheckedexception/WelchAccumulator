package welch;

import welch.Queue;
import java.lang.Math;
import org.jtransforms.fft.*;

class NoiseComputer extends Thread {
	private static final double NOISE_EST_PERCENT = 0.1;  // The percent of the periodogram (on the end) to use for the noise estimation.
	private static final double SCALE_FACTOR = .221735;
	
	private int segLength;
	private int frameSize;
	private double[] window;

	private double scale;

	private double[][] data;

	private DoubleFFT_1D fft;

	private int spectrumLength;
	public double[] periodogram;
	
	double result;

	public NoiseComputer(int segLength, int frameSize, double[] window) {
		this.segLength = segLength;
		this.frameSize = frameSize;
		
		if (window != null && window.length == segLength) {
			this.window = window;
	    } else if (window == null){
	    	this.window = new double[segLength];
	    	for (int i = 0; i < segLength; i++)
	    		this.window[i] = 0.54 - 0.46 * (double) Math.cos(2 * Math.PI * i / (segLength - 1)); // If null, use the hamming window
	    } else {
	    	this.window = new double[segLength];
	    	for (int i = 0; i < segLength; i++)
	    		this.window[i] = 1; // If window is the incorrect length, don't use a window
	    }
		
		this.spectrumLength = (int) (this.segLength / 2 + 1);
		setPeriodogramScale();

		this.data = new double[this.frameSize][this.segLength];

		fft = new DoubleFFT_1D(this.segLength);

		this.periodogram = new double[this.spectrumLength];
	}

	// Sets this.scale for computing the periodogram later
	private void setPeriodogramScale() {
		double sum = 0;

		for (int i = 0; i < segLength; i++)
			sum += Math.pow(this.window[i], 2);

		this.scale = 1.0 / sum;
	}

	/*
	 * Creates a signal by:
	 * 1) Extracting "shift" from this.data
	 * 2) Demeaning the signal
	 * 3) Windowing the signal
	 */
	private void createSignal(double[] signal, int shift) {
		double mean = 0;

		for (int i = 0; i < this.segLength; i++)
			mean += this.data[shift][i];

		mean /= this.segLength;

		for (int i = 0; i < this.segLength; i++) {
			signal[i] = this.data[shift][i];
			signal[i] -= mean; //For some reason, this introduces numerical error when compared to scipy.signal.welch
			signal[i] *= this.window[i];
		}
	}

	/* 
	 * Converts a real spectrum from JTransforms to a periodogram by:
	 * 1) Putting the results from JTransforms in order
	 * 2) Finding the squared magnitude of each value of the spectrum
	 * 3) Scaling (most) values by 2 to conserve the energy
	 * 4) Scaling by this.scale
	 */
	private void realSpectrumToPeriodogram(double[] spectrum, double[] periodogram) {
		for (int i = 0; i < this.spectrumLength; i ++) {
			periodogram[i] = 0;
			if (this.segLength % 2 == 1) {
				// If the signal length(n) was odd, spectrum is in the following format:
				// a[2i] = Re[i], 0<=k<(n+1)/2
				// a[2i+1] = Im[i], 0<k<(n-1)/2
				// a[1] = Im[(n-1)/2]

				periodogram[i] += Math.pow(spectrum[2*i], 2); // Re[i]

				if (i == this.spectrumLength - 1)
					periodogram[i] += Math.pow(spectrum[1], 2); // Im[(n-1)/2]
				else if (i != 0)
					periodogram[i] += Math.pow(spectrum[2*i+1], 2); // Im[i]

				if (i != 0)
					periodogram[i] *= 2; // Preserve energy in signal
			}else{
				// If the signal length(n) was even, spectrum is in the following format:
				// a[2i] = Re[i], 0<=k<n/2
				// a[2i+1] = Im[i], 0<k<n/2
				// a[1] = Re[n/2]

				if (i == this.spectrumLength - 1)
					periodogram[i] += Math.pow(spectrum[1], 2); // Re[n/2]
				else
					periodogram[i] += Math.pow(spectrum[2*i], 2); // Re[i]

				if (i > 0 && i < this.spectrumLength - 1) {
					periodogram[i] += Math.pow(spectrum[2*i+1], 2); // Im[i]
					periodogram[i] *= 2; // Preserve energy in signal
				}
			}

			periodogram[i] *= this.scale;
		}
	}

	/* 
	 * Computes the periodogram of all shifts in the last segment by:
	 * 1) Looping through every frame
	 * 2) Creating a signal for each frame using createSignal()
	 * 3) Taking the fourier transform of the signal with realForward()
	 * 4) Converting the fourier transform to a periodogram with realSpectrumToPeriodogram()
	 * 5) Taking the average of all periodograms
	 */
	private void computePeriodogram() {
		double[] signal = new double[this.segLength];
		double[] periodogram = new double[this.spectrumLength];

		for (int i = 0; i < this.spectrumLength; i++)
			this.periodogram[i] = 0;

		for (int frame = 0; frame < this.frameSize; frame++) {
			createSignal(signal, frame);

			fft.realForward(signal);
			realSpectrumToPeriodogram(signal, periodogram);

			for (int j = 0; j < this.spectrumLength; j++)
				this.periodogram[j] += periodogram[j] / this.frameSize;
		}
	}
	
	/*
	 * Uses the periodogram to compute the noise floor of the signal by:
	 * 1) Looking at the last NOISE_EST_PERCENT percent of the periodogram
	 * 2) Taking the average of those values
	 * 3) Computing the result 
	 */
	private void computeNoise() {
		int asymptoteLength = (int) (this.spectrumLength * NoiseComputer.NOISE_EST_PERCENT);
		double asymptote = 0;
		if (asymptoteLength >= 1) {
			for (int i = this.spectrumLength - 1; i >= this.spectrumLength - asymptoteLength; i--) {
				asymptote += this.periodogram[i];
			}
			asymptote /= asymptoteLength;
		}else {
			// If the data is too short, and NOISE_EST_PERCENT * length is less than one value, just use the last value
			asymptote = this.periodogram[this.spectrumLength];
		}
		// Now that we've approximated the asymptote, use it to find the noiseFloor
		this.result = 1 / NoiseComputer.SCALE_FACTOR * Math.sqrt(asymptote / 2);
	}
	
	private boolean addResultToQueue() {
		if (Queue.done)
			return false;
		
		try {
			return Queue.insertIntoResultQueue(this.result);
		} catch (InterruptedException e) {
			Queue.done = true;
			return false;
		}
	}
	
	private boolean setDataFromQueue() {
		if (Queue.done)
			return false;
		
		try {
			double[][] data = Queue.getFromDataQueue();
			
			if (data == null) return false;
			
			this.data = data;
		} catch (InterruptedException e) {
			Queue.done = true;
			return false;
		}
		
		return true;
	}

	public void run() {
		while (!Queue.done) {
			if (setDataFromQueue())
			{
				computePeriodogram();
				
				computeNoise();
				
				addResultToQueue();
			}
		}
	}
}