package org.datavec.spark.transform;

import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.sql.Column;
import org.apache.spark.sql.DataFrame;
import org.apache.spark.sql.Row;
import static org.apache.spark.sql.functions.*;
import org.datavec.api.transform.schema.Schema;
import org.datavec.api.writable.Writable;


import java.util.ArrayList;
import java.util.List;


/**
 * Simple dataframe based normalization.
 * Column based transforms such as min/max scaling
 * based on column min max and zero mean unit variance
 * using column wise statistics.
 *
 * @author Adam Gibson
 */
public class Normalization {





    /**
     * Normalize by zero mean unit variance
     * @param frame the data to normalize
     * @return a zero mean unit variance centered
     * rdd
     */
    public static DataFrame zeromeanUnitVariance(DataFrame frame) {
        return zeromeanUnitVariance(frame,new ArrayList<String>());
    }

    /**
     * Normalize by zero mean unit variance
     * @param schema the schema to use
     *               to create the data frame
     * @param data the data to normalize
     * @return a zero mean unit variance centered
     * rdd
     */
    public static JavaRDD<List<Writable>> zeromeanUnitVariance(Schema schema, JavaRDD<List<Writable>> data) {
        return zeromeanUnitVariance(schema,data,new ArrayList<String>());
    }

    /**
     * Scale based on min,max
     * @param dataFrame the dataframe to scale
     * @param min the minimum value
     * @param max the maximum value
     * @return the normalized dataframe per column
     */
    public static DataFrame normalize(DataFrame dataFrame,double min,double max) {
        return normalize(dataFrame,min,max,new ArrayList<String>());
    }

    /**
     * Scale based on min,max
     * @param schema the schema of the data to scale
     * @param data the data to sclae
     * @param min the minimum value
     * @param max the maximum value
     * @return the normalized ata
     */
    public static JavaRDD<List<Writable>> normalize(Schema schema, JavaRDD<List<Writable>> data,double min,double max) {
        DataFrame frame = DataFrames.toDataFrame(schema,data);
        return DataFrames.toRecords(normalize(frame,min,max,new ArrayList<String>())).getSecond();
    }



    /**
     * Scale based on min,max
     * @param dataFrame the dataframe to scale
     * @return the normalized dataframe per column
     */
    public static DataFrame normalize(DataFrame dataFrame) {
        return normalize(dataFrame,0,1,new ArrayList<String>());
    }

    /**
     * Scale all data  0 to 1
     * @param schema the schema of the data to scale
     * @param data the data to scale
     * @return the normalized ata
     */
    public static JavaRDD<List<Writable>> normalize(Schema schema, JavaRDD<List<Writable>> data) {
        return normalize(schema,data,0,1,new ArrayList<String>());
    }









    /**
     * Normalize by zero mean unit variance
     * @param frame the data to normalize
     * @return a zero mean unit variance centered
     * rdd
     */
    public static DataFrame zeromeanUnitVariance(DataFrame frame,List<String> skipColumns) {
        String[] columnNames = frame.columns();
        for(String columnName : columnNames) {
            if(skipColumns.contains(columnName)) {
                frame = frame.withColumn(columnName,frame.col(columnName));
            }
            else {
                Column mean = DataFrames.mean(frame,columnName);
                Column std = DataFrames.std(frame,columnName);
                frame = frame.withColumn(columnName,frame.col(columnName).minus(mean).divide(std.plus(1e-6)));
            }

        }

        return frame;
    }

    /**
     * Normalize by zero mean unit variance
     * @param schema the schema to use
     *               to create the data frame
     * @param data the data to normalize
     * @return a zero mean unit variance centered
     * rdd
     */
    public static JavaRDD<List<Writable>> zeromeanUnitVariance(Schema schema, JavaRDD<List<Writable>> data,List<String> skipColumns) {
        DataFrame frame = DataFrames.toDataFrame(schema,data);
        return DataFrames.toRecords(zeromeanUnitVariance(frame,skipColumns)).getSecond();
    }

    public static JavaRDD<List<List<Writable>>> zeroMeanUnitVarianceSequence(Schema schema, JavaRDD<List<List<Writable>>> sequence){
        DataFrame frame = DataFrames.sequenceToDataFrame(schema, sequence);
        zeromeanUnitVariance(frame);
        return DataFrames.toRecordsSequence(frame).getSecond();
    }

    /**
     * Scale based on min,max
     * @param dataFrame the dataframe to scale
     * @param min the minimum value
     * @param max the maximum value
     * @return the normalized dataframe per column
     */
    public static DataFrame normalize(DataFrame dataFrame,double min,double max,List<String> skipColumns) {
        String[] columnNames = dataFrame.columns();


        List<String> toProcess = new ArrayList<>();
        for(String columnName : columnNames){
            if(DataFrames.SEQUENCE_UUID_COLUMN.equals(columnName)) continue;
            if(DataFrames.SEQUENCE_INDEX_COLUMN.equals(columnName)) continue;

            toProcess.add(columnName);
        }

        dataFrame.show();

        for(String columnName : columnNames) {
            if(DataFrames.SEQUENCE_UUID_COLUMN.equals(columnName)) continue;
            if(DataFrames.SEQUENCE_INDEX_COLUMN.equals(columnName)) continue;
            DataFrame minMax = dataFrame.select(columnName).agg(min(columnName), max(columnName));
            Row r = minMax.collect()[0];
            double dMin = ((Number)r.get(0)).doubleValue();
            double dMax = ((Number)r.get(1)).doubleValue();

            double maxSubMin = dMax - dMin;
            if(maxSubMin == 0) maxSubMin = 1;

            System.out.println(dMin + "\t" + dMax);


//            Column newCol = dataFrame.col(columnName).minus(min2).divide(maxMinusMin.plus(1e-6)).multiply(max - min).plus(min);
            Column newCol = dataFrame.col(columnName).minus(dMin).divide(maxSubMin).multiply(max - min).plus(min);
            newCol.explain(true);
            dataFrame = dataFrame.withColumn(columnName,newCol);
            System.out.println("+++++ DONE COLUMN: " + columnName + " ++++++");

//            if(skipColumns.contains(columnName))
//                continue;
//            Column min2 = DataFrames.min(dataFrame,columnName);
//            Column max2 = DataFrames.max(dataFrame,columnName);
//            Column maxMinusMin = max2.minus(min2);
//            dataFrame = dataFrame.withColumn(columnName,dataFrame.col(columnName).minus(min2).divide(maxMinusMin.plus(1e-6)).multiply(max - min).plus(min));
        }

        return dataFrame;
    }

    /**
     * Scale based on min,max
     * @param schema the schema of the data to scale
     * @param data the data to sclae
     * @param min the minimum value
     * @param max the maximum value
     * @return the normalized ata
     */
    public static JavaRDD<List<Writable>> normalize(Schema schema, JavaRDD<List<Writable>> data,double min,double max,List<String> skipColumns) {
        DataFrame frame = DataFrames.toDataFrame(schema,data);
        return DataFrames.toRecords(normalize(frame,min,max,skipColumns)).getSecond();
    }

    public static JavaRDD<List<List<Writable>>> normalizeSequence(Schema schema, JavaRDD<List<List<Writable>>> data, double min, double max){
        DataFrame frame = DataFrames.sequenceToDataFrame(schema,data);
        return DataFrames.toRecordsSequence(normalize(frame,min,max)).getSecond();
    }


    /**
     * Scale based on min,max
     * @param dataFrame the dataframe to scale
     * @return the normalized dataframe per column
     */
    public static DataFrame normalize(DataFrame dataFrame,List<String> skipColumns) {
        return normalize(dataFrame,0,1,skipColumns);
    }

    /**
     * Scale all data  0 to 1
     * @param schema the schema of the data to scale
     * @param data the data to scale
     * @return the normalized ata
     */
    public static JavaRDD<List<Writable>> normalize(Schema schema, JavaRDD<List<Writable>> data,List<String> skipColumns) {
        return normalize(schema,data,0,1,skipColumns);
    }
}
