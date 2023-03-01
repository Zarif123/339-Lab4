package simpledb.optimizer;

import simpledb.execution.Predicate;

/**
 * A class to represent a fixed-width histogram over a single integer-based field.
 */
public class IntHistogram {

    private int buckets;
    private int min;
    private int max;
    //array of length buckets for the histogram
    private int[] graph; 
    //counts number of tuples
    private int ntups;
    //range per bucket
    private double rangeperbucket;
    
    /**
     * Create a new IntHistogram.
     * <p>
     * This IntHistogram should maintain a histogram of integer values that it receives.
     * It should split the histogram into "buckets" buckets.
     * <p>
     * The values that are being histogrammed will be provided one-at-a-time through the "addValue()" function.
     * <p>
     * Your implementation should use space and have execution time that are both
     * constant with respect to the number of values being histogrammed.  For example, you shouldn't
     * simply store every value that you see in a sorted list.
     *
     * @param buckets The number of buckets to split the input value into.
     * @param min     The minimum integer value that will ever be passed to this class for histogramming
     * @param max     The maximum integer value that will ever be passed to this class for histogramming
     */
    public IntHistogram(int buckets, int min, int max) {
        // TODO: some code goes here
        this.buckets = buckets;
        this.min = min;
        this.max = max;
        this.graph = new int[buckets];
        this.ntups = 0;
        this.rangeperbucket = (double) (max + 1 - min) / buckets;
    }
    
    //helper I made
    private int getBucket(int v) {
        // calculate the correct slot
        // min + rangeperbucket * bucketNo = v
        // bucketNo = (v - min) / rangeperbucket
        // but rounded down
        return (int) ((v - min) / rangeperbucket);
    }

    /**
     * Add a value to the set of values that you are keeping a histogram of.
     *
     * @param v Value to add to the histogram
     */
    public void addValue(int v) {
        // TODO: some code goes here
        int bucketNo = this.getBucket(v);
        graph[bucketNo]++;
        ntups++;
    }

    /**
     * Estimate the selectivity of a particular predicate and operand on this table.
     * <p>
     * For example, if "op" is "GREATER_THAN" and "v" is 5,
     * return your estimate of the fraction of elements that are greater than 5.
     *
     * @param op Operator
     * @param v  Value
     * @return Predicted selectivity of this particular operator and value
     */
    public double estimateSelectivity(Predicate.Op op, int v) {
        // TODO: some code goes here
        //might be some double casting issues to look out for
        double select = 0.0;
        String oper = op.toString();
        int bucketNo = this.getBucket(v);
        if (bucketNo < 0 || bucketNo >= this.buckets) {
            bucketNo = 0;
        }
        //I think LIKE never runs
        if (oper == "LIKE") {
            return -1.0;
        }
        //anything involving equality
        if (oper == "=" || oper == "<>" || oper == "<=" || oper == ">=") {
            select += (graph[bucketNo] / this.rangeperbucket) / this.ntups;
            if (oper == "=") {
                return select;
            }
            if (oper == "<>") {
                return 1.0 - select;
            }
        }
        //now the range operations
        double bucketf = (double) graph[bucketNo] / ntups;
        if (oper == "<" || oper == "<=") {
            //all less than operations
            //get value for singular bucket
            double left = min + rangeperbucket * bucketNo;
            double part = (v - left) / rangeperbucket;
            select += part * bucketf;
            //get the rest 
            for (int i = bucketNo - 1; i > -1; i--) {
                select += (double) graph[i] / ntups;
            }
        } else {
            //all greater than operations
            //get value for singular bucket
            double right = min + rangeperbucket * (bucketNo + 1);
            double part = (right - v) / rangeperbucket;
            select += part * bucketf;
            //get the rest
            for (int i = bucketNo + 1; i < buckets; i++) {
                select += (double) graph[i] / ntups;
            }
        }
        return select;
    }

    /**
     * @return the average selectivity of this histogram.
     *         <p>
     *         This is not an indispensable method to implement the basic
     *         join optimization. It may be needed if you want to
     *         implement a more efficient optimization
     */
    public double avgSelectivity() {
        // TODO: some code goes here
        //using the formula of (average(h) / w) / ntups
        double avgHeight = 0.0;
        for (int i = 0; i < buckets; i++) {
            avgHeight += graph[i];
        }
        avgHeight /= buckets;
        return (avgHeight / this.rangeperbucket) / this.ntups;
    }

    /**
     * @return A string describing this histogram, for debugging purposes
     */
    public String toString() {
        // TODO: some code goes here
        String s = "";
        for (int i = 0; i < buckets; i++) {
            s += "Bucket # " + i + ": Height " + graph[i];
            s += " From " + (min + rangeperbucket * i) + " To " + (min + rangeperbucket * (i + 1)) + "\n";
        }
        return s;
    }
}
