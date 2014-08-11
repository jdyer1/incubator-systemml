/**
 * IBM Confidential
 * OCO Source Materials
 * (C) Copyright IBM Corp. 2010, 2014
 * The source code for this program is not published or otherwise divested of its trade secrets, irrespective of what has been deposited with the U.S. Copyright Office.
 */

package com.ibm.bi.dml.test.components.runtime.util;

import org.junit.Assert;
import org.junit.Test;

import com.ibm.bi.dml.runtime.controlprogram.parfor.stat.Timing;
import com.ibm.bi.dml.runtime.matrix.io.MatrixBlock;
import com.ibm.bi.dml.runtime.util.DataConverter;
import com.ibm.bi.dml.runtime.util.LocalFileUtils;
import com.ibm.bi.dml.runtime.util.MapReduceTool;
import com.ibm.bi.dml.test.utils.TestUtils;


public class LocalFileSerializeTest 
{
	@SuppressWarnings("unused")
	private static final String _COPYRIGHT = "Licensed Materials - Property of IBM\n(C) Copyright IBM Corp. 2010, 2014\n" +
                                             "US Government Users Restricted Rights - Use, duplication  disclosure restricted by GSA ADP Schedule Contract with IBM Corp.";
	
	private static final int _rows1 = 3500;
	private static final int _cols1 = 3600;
	private static final int _rows2 = 15;
	private static final int _cols2 = 23;

	private static final double _sparsity1 = 0.7;
	private static final double _sparsity2 = 0.07;
	
	
	private String _fname = "./A";
	
	@Test
	public void testLocalFileSerializeDenseLarge() 
	{
		testLocalFileSerialize( false, true );
	}
	
	@Test
	public void testLocalFileSerializeSparseLarge() 
	{
		testLocalFileSerialize( true, true );
	}
	
	@Test
	public void testLocalFileSerializeDenseSmall() 
	{
		testLocalFileSerialize( false, false );
	}
	
	@Test
	public void testLocalFileSerializeSparseSmall() 
	{
		testLocalFileSerialize( true, false );
	}
	
	
	
	private void testLocalFileSerialize( boolean sparse, boolean large )
	{
		int rows = large?_rows1:_rows2;
		int cols = large?_cols1:_cols2;
		double sparsity = sparse?_sparsity2:_sparsity1;
				
		//create initial matrix
		double[][] matrix = TestUtils.generateTestMatrix(rows, cols, 0, 1, sparsity, 7);
		double[][] matrix2 = null;
		
		try 
		{
			MatrixBlock mb1 = DataConverter.convertToMatrixBlock(matrix);			

			//serialize
			Timing time = new Timing(true);
			LocalFileUtils.writeMatrixBlockToLocal(_fname, mb1);			
			double t1 = time.stop();

			//deserialize
			Timing time2 = new Timing(true);
			MatrixBlock mb2 = LocalFileUtils.readMatrixBlockFromLocal(_fname);			
			double t2 = time2.stop();
			
			matrix2 = DataConverter.convertToDoubleMatrix(mb2);
			
			//cleanup
			MapReduceTool.deleteFileIfExistOnHDFS(_fname);
			
			System.out.println("Write = "+t1+", Read = "+t2);
		} 
		catch(Exception e) 
		{
			e.printStackTrace();
		}
		
		//compare
		for( int i=0; i<rows; i++ )
			for( int j=0; j<cols; j++ )
				if( matrix[i][j]!=matrix2[i][j] )
					Assert.fail("Wrong value i="+i+", j="+j+", value1="+matrix[i][j]+", value2="+matrix2[i][j]);
	}
}