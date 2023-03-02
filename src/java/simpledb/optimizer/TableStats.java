package simpledb.optimizer;

import simpledb.common.Database;
import simpledb.common.Type;
import simpledb.execution.Predicate;
import simpledb.execution.SeqScan;
import simpledb.storage.*;
import simpledb.transaction.Transaction;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * TableStats represents statistics (e.g., histograms) about base tables in a
 * query.
 * <p>
 * This class is not needed in implementing lab1 and lab2.
 */
public class TableStats {

    private static final ConcurrentMap<String, TableStats> statsMap = new ConcurrentHashMap<>();

    static final int IOCOSTPERPAGE = 1000;

    public static TableStats getTableStats(String tablename) {
        return statsMap.get(tablename);
    }

    public static void setTableStats(String tablename, TableStats stats) {
        statsMap.put(tablename, stats);
    }

    public static void setStatsMap(Map<String, TableStats> s) {
        try {
            java.lang.reflect.Field statsMapF = TableStats.class.getDeclaredField("statsMap");
            statsMapF.setAccessible(true);
            statsMapF.set(null, s);
        } catch (NoSuchFieldException | IllegalAccessException | IllegalArgumentException | SecurityException e) {
            e.printStackTrace();
        }

    }

    public static Map<String, TableStats> getStatsMap() {
        return statsMap;
    }

    public static void computeStatistics() {
        Iterator<Integer> tableIt = Database.getCatalog().tableIdIterator();

        System.out.println("Computing table stats.");
        while (tableIt.hasNext()) {
            int tableid = tableIt.next();
            TableStats s = new TableStats(tableid, IOCOSTPERPAGE);
            setTableStats(Database.getCatalog().getTableName(tableid), s);
        }
        System.out.println("Done.");
    }

    /**
     * Number of bins for the histogram. Feel free to increase this value over
     * 100, though our tests assume that you have at least 100 bins in your
     * histograms.
     */
    static final int NUM_HIST_BINS = 100;
    
    private int tableid;
    private int ioCostPerPage;
    //other fields
    private int tcard;
    private TupleDesc td;
    private ArrayList<Integer> intInds;
    private ArrayList<Integer> strInds;
    private ArrayList<IntHistogram> intHists;
    private ArrayList<StringHistogram> strHists;
    private int numtups;

    /**
     * Create a new TableStats object, that keeps track of statistics on each
     * column of a table
     *
     * @param tableid       The table over which to compute statistics
     * @param ioCostPerPage The cost per page of IO. This doesn't differentiate between
     *                      sequential-scan IO and disk seeks.
     */
    public TableStats(int tableid, int ioCostPerPage) {
        // For this function, you'll have to get the
        // DbFile for the table in question,
        // then scan through its tuples and calculate
        // the values that you need.
        // You should try to do this reasonably efficiently, but you don't
        // necessarily have to (for example) do everything
        // in a single scan of the table.
        // TODO: some code goes here
        this.tableid = tableid;
        this.ioCostPerPage = ioCostPerPage;
        DbFile dbf = Database.getCatalog().getDatabaseFile(tableid);
        this.td = dbf.getTupleDesc();
        //tcard should be the number of pages in the table 
        this.tcard = dbf.numPages();
        //need to scan table
        HeapFileIterator tableIt = dbf.iterator();
        //scan 1: find the max and min of each field for int histograms
        tableIt.open();
        //log indices that have array vs string inds
        this.intInds = new ArrayList<Integer>();
        this.strInds = new ArrayList<Integer>();
        for (int i = 0; i < this.td.numFields(); i++) {
            if (this.td.getFieldType(i) == Type.INT_TYPE) {
                intInds.add(i);
            } else {
                strInds.add(i);
            }
        }
        //keep track of [min, max] for all int fields
        ArrayList<IntField[]> hiLo = new ArrayList<IntField[]>();
        boolean starting = true;
        this.numtups = 0;
        while (tableIt.hasNext()) {
            Tuple temp = tableIt.next();
            //looping over intInds
            for (int i = 0; i < intInds.size(); i++) {
                int tupInd = intInds.get(i);
                IntField fval = temp.getField(tupInd);
                //case for start: intiialize everything and set
                if (starting) {
                    IntField[] startHiLo = {fval, fval};
                    hiLo.add(startHiLo);
                } else {
                    //no more than 1 if statement can pass each iteration
                    IntField lower = hiLo.get(i)[0];
                    IntField upper = hiLo.get(i)[1];
                    //check for min
                    if (fval.compare(Predicate.Op.LESS_THAN, lower)) {
                        hiLo.set(i, {fval, upper});
                    }
                    //check for max
                    if (fval.compare(Predicate.Op.GREATER_THAN, upper)) {
                        hiLo.set(i, {lower, fval});
                    }
                }
            }
            starting = false;
            this.numtups++;
        }
        //make histograms with these values 
        this.intHists = new ArrayList<IntHistogram>();
        this.strHists = new ArrayList<StringHistogram>();
        int intI = 0;
        for (int i = 0; i < this.td.numFields(); i++) {
            if (this.td.getFieldType(i) == Type.INT_TYPE) {
                IntField[] tempEntry = hiLo.get(intI);
                intHists.add(new IntHistogram(NUM_HIST_BINS, tempEntry[0].getValue(), tempEntry[1].getValue()));
                intI++;
            } else {
                strHists.add(new StringHistogram(NUM_HIST_BINS));
            }
        }
        //scan 2, read all values into their histograms
        tableIt.rewind();
        while (tableIt.hasNext()) {
            Tuple temp = tableIt.next();
            intI = 0;
            int strI = 0;
            for (int i = 0; i < this.td.numFields(); i++) {
                if (this.td.getFieldType(i) == Type.INT_TYPE) {
                    IntHistogram tempHist = intHists.get(intI);
                    tempHist.addValue(temp.getField(i).getValue());
                    intHists.set(intI, tempHist);
                    intI++;
                } else {
                    StringHistogram tempHist = strHists.get(strI);
                    tempHist.addValue(temp.getField(i).getValue());
                    strHists.set(strI, tempHist);
                    strI++;
                }
            }
        }
        tableIt.close();
    }

    /**
     * Estimates the cost of sequentially scanning the file, given that the cost
     * to read a page is costPerPageIO. You can assume that there are no seeks
     * and that no pages are in the buffer pool.
     * <p>
     * Also, assume that your hard drive can only read entire pages at once, so
     * if the last page of the table only has one tuple on it, it's just as
     * expensive to read as a full page. (Most real hard drives can't
     * efficiently address regions smaller than a page at a time.)
     *
     * @return The estimated cost of scanning the table.
     */
    public double estimateScanCost() {
        // TODO: some code goes here
        return (double) tcard * ioCostPerPage;
    }

    /**
     * This method returns the number of tuples in the relation, given that a
     * predicate with selectivity selectivityFactor is applied.
     *
     * @param selectivityFactor The selectivity of any predicates over the table
     * @return The estimated cardinality of the scan with the specified
     *         selectivityFactor
     */
    public int estimateTableCardinality(double selectivityFactor) {
        // TODO: some code goes here
        return (int) selectivityFactor * totalTuples();
    }

    /**
     * The average selectivity of the field under op.
     *
     * @param field the index of the field
     * @param op    the operator in the predicate
     *              The semantic of the method is that, given the table, and then given a
     *              tuple, of which we do not know the value of the field, return the
     *              expected selectivity. You may estimate this value from the histograms.
     */
    public double avgSelectivity(int field, Predicate.Op op) {
        // TODO: some code goes here
        //not sure if we need this or if it's right
        //look in intHist
        for (int i = 0; i < intInds.size(); i++) {
            if (intInds.get(i) == field) {
                return intHists.get(i).avgSelectivity();
            }
        }
        //look in strHist
        for (int i = 0; i < strInds.size(); i++) {
            if (strInds.get(i) == field) {
                return strHists.get(i).avgSelectivity();
            }
        }
        //should never get to here
        return -1.0;
    }

    /**
     * Estimate the selectivity of predicate <tt>field op constant</tt> on the
     * table.
     *
     * @param field    The field over which the predicate ranges
     * @param op       The logical operation in the predicate
     * @param constant The value against which the field is compared
     * @return The estimated selectivity (fraction of tuples that satisfy) the
     *         predicate
     */
    public double estimateSelectivity(int field, Predicate.Op op, Field constant) {
        // TODO: some code goes here
        //look in intHist
        for (int i = 0; i < intInds.size(); i++) {
            if (intInds.get(i) == field && constant.getType() == Type.INT_TYPE) {
                return intHists.get(i).estimateSelectivity(op, constant.getValue());
            }
        }
        //look in strHist
        for (int i = 0; i < strInds.size(); i++) {
            if (strInds.get(i) == field && constant.getType() == Type.STRING_TYPE) {
                return strHists.get(i).estimateSelectivity(op, constant.getValue());
            }
        }
        //should never get to here
        return -1.0;
    }

    /**
     * return the total number of tuples in this table
     */
    public int totalTuples() {
        // TODO: some code goes here
        //not sure how else to do it
        /*int bitsPerTupleIncludingHeader = td.getSize() * 8 + 1;
        return (tcard * BufferPool.getPageSize() * 8) / bitsPerTupleIncludingHeader;*/
        return this.numtups;
    }

}
