/**
 * IBM Confidential
 * OCO Source Materials
 * (C) Copyright IBM Corp. 2010, 2013
 * The source code for this program is not published or otherwise divested of its trade secrets, irrespective of what has been deposited with the U.S. Copyright Office.
 */

package com.ibm.bi.dml.meta;

import java.io.IOException;

import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.MapReduceBase;
import org.apache.hadoop.mapred.Mapper;
import org.apache.hadoop.mapred.OutputCollector;
import org.apache.hadoop.mapred.Reporter;
import org.apache.hadoop.mapred.lib.MultipleOutputs;

import com.ibm.bi.dml.runtime.matrix.mapred.MRJobConfiguration;


public class PartitionBlockJoinMapperIDTable extends MapReduceBase
implements Mapper<Writable, Writable, LongWritable, BlockJoinMapOutputValue> 
{ //TODO change read key/val format

	@SuppressWarnings("unused")
	private static final String _COPYRIGHT = "Licensed Materials - Property of IBM\n(C) Copyright IBM Corp. 2010, 2013\n" +
                                             "US Government Users Restricted Rights - Use, duplication  disclosure restricted by GSA ADP Schedule Contract with IBM Corp.";
	
	//private Converter inputConverter=null;	//do i need this for mine?? to get seq file key/val format correctly?!
	PartitionParams pp = new PartitionParams() ;
	//int brlen, bclen ;
	BlockJoinMapperMethodIDTable bmm ;
	MultipleOutputs multipleOutputs ;

	@SuppressWarnings("unchecked")
	@Override
	public void map(Writable rawKey, Writable rawValue,
			OutputCollector<LongWritable, BlockJoinMapOutputValue> out, Reporter reporter)
	throws IOException {
		bmm.execute((LongWritable)rawKey, (WritableLongArray) rawValue, reporter, out);
	}
	
	public void close() throws IOException  {
		multipleOutputs.close();
	}
	//TODO: change the reading configs and format for seq file reading in!!!
	@Override
	public void configure(JobConf job) {
		multipleOutputs = new MultipleOutputs(job) ;
		pp = MRJobConfiguration.getPartitionParams(job) ;		
		//only row partitioning for join
		if((pp.isEL == false && (pp.cvt == PartitionParams.CrossvalType.kfold || pp.cvt == PartitionParams.CrossvalType.holdout)) || 
					(pp.isEL == true && (pp.et == PartitionParams.EnsembleType.rsm || pp.et == PartitionParams.EnsembleType.rowholdout)))
			bmm = new HoldoutKFoldBlockJoinMapperMethodIDTable(pp, multipleOutputs) ;
		else if((pp.isEL == false && pp.cvt == PartitionParams.CrossvalType.bootstrap) ||
					(pp.isEL == true && pp.et == PartitionParams.EnsembleType.bagging))
			bmm = new BootstrapBlockJoinMapperMethodIDTable(pp, multipleOutputs) ;
	}
}