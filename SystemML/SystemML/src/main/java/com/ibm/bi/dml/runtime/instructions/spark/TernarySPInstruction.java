package com.ibm.bi.dml.runtime.instructions.spark;

import java.util.ArrayList;
import java.util.Map.Entry;

import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.function.Function;
import org.apache.spark.api.java.function.Function2;
import org.apache.spark.api.java.function.PairFlatMapFunction;
import org.apache.spark.api.java.function.PairFunction;
import org.apache.spark.storage.StorageLevel;

import scala.Tuple2;

import com.ibm.bi.dml.lops.Ternary;
import com.ibm.bi.dml.parser.Expression.DataType;
import com.ibm.bi.dml.parser.Expression.ValueType;
import com.ibm.bi.dml.runtime.DMLRuntimeException;
import com.ibm.bi.dml.runtime.DMLUnsupportedOperationException;
import com.ibm.bi.dml.runtime.controlprogram.context.ExecutionContext;
import com.ibm.bi.dml.runtime.controlprogram.context.SparkExecutionContext;
import com.ibm.bi.dml.runtime.instructions.Instruction;
import com.ibm.bi.dml.runtime.instructions.InstructionUtils;
import com.ibm.bi.dml.runtime.instructions.cp.CPOperand;
import com.ibm.bi.dml.runtime.matrix.MatrixCharacteristics;
import com.ibm.bi.dml.runtime.matrix.data.CTableMap;
import com.ibm.bi.dml.runtime.matrix.data.MatrixBlock;
import com.ibm.bi.dml.runtime.matrix.data.MatrixCell;
import com.ibm.bi.dml.runtime.matrix.data.MatrixIndexes;
import com.ibm.bi.dml.runtime.matrix.data.OperationsOnMatrixValues;
import com.ibm.bi.dml.runtime.matrix.mapred.IndexedMatrixValue;
import com.ibm.bi.dml.runtime.matrix.operators.Operator;
import com.ibm.bi.dml.runtime.matrix.operators.SimpleOperator;
import com.ibm.bi.dml.runtime.util.UtilFunctions;

public class TernarySPInstruction extends ComputationSPInstruction
{
	@SuppressWarnings("unused")
	private static final String _COPYRIGHT = "Licensed Materials - Property of IBM\n(C) Copyright IBM Corp. 2010, 2015\n" +
                                             "US Government Users Restricted Rights - Use, duplication  disclosure restricted by GSA ADP Schedule Contract with IBM Corp.";
	
	private String _outDim1;
	private String _outDim2;
	private boolean _dim1Literal; 
	private boolean _dim2Literal;
	private boolean _isExpand;
	private boolean _ignoreZeros;
	
	public TernarySPInstruction(Operator op, CPOperand in1, CPOperand in2, CPOperand in3, CPOperand out, 
							 String outputDim1, boolean dim1Literal,String outputDim2, boolean dim2Literal, 
							 boolean isExpand, boolean ignoreZeros, String opcode, String istr )
	{
		super(op, in1, in2, in3, out, opcode, istr);
		_outDim1 = outputDim1;
		_dim1Literal = dim1Literal;
		_outDim2 = outputDim2;
		_dim2Literal = dim2Literal;
		_isExpand = isExpand;
		_ignoreZeros = ignoreZeros;
	}

	public static TernarySPInstruction parseInstruction(String inst) throws DMLRuntimeException{
		
		InstructionUtils.checkNumFields ( inst, 7 );
		
		String[] parts = InstructionUtils.getInstructionPartsWithValueType(inst);
		String opcode = parts[0];
		
		//handle opcode
		if ( !(opcode.equalsIgnoreCase("ctable") || opcode.equalsIgnoreCase("ctableexpand")) ) {
			throw new DMLRuntimeException("Unexpected opcode in TertiarySPInstruction: " + inst);
		}
		boolean isExpand = opcode.equalsIgnoreCase("ctableexpand");
		
		//handle operands
		CPOperand in1 = new CPOperand(parts[1]);
		CPOperand in2 = new CPOperand(parts[2]);
		CPOperand in3 = new CPOperand(parts[3]);
		
		//handle known dimension information
		String[] dim1Fields = parts[4].split(Instruction.LITERAL_PREFIX);
		String[] dim2Fields = parts[5].split(Instruction.LITERAL_PREFIX);

		CPOperand out = new CPOperand(parts[6]);
		boolean ignoreZeros = Boolean.parseBoolean(parts[7]);
		
		// ctable does not require any operator, so we simply pass-in a dummy operator with null functionobject
		return new TernarySPInstruction(new SimpleOperator(null), in1, in2, in3, out, dim1Fields[0], Boolean.parseBoolean(dim1Fields[1]), dim2Fields[0], Boolean.parseBoolean(dim2Fields[1]), isExpand, ignoreZeros, opcode, inst);
	}

	private Ternary.OperationTypes findCtableOperation() {
		DataType dt1 = input1.getDataType();
		DataType dt2 = input2.getDataType();
		DataType dt3 = input3.getDataType();
		return Ternary.findCtableOperationByInputDataTypes(dt1, dt2, dt3);
	}
	
	public static class MapTwoMBIterableIntoAL implements PairFunction<Tuple2<MatrixIndexes,Tuple2<Iterable<MatrixBlock>,Iterable<MatrixBlock>>>, MatrixIndexes, ArrayList<MatrixBlock>> {

		private static final long serialVersionUID = 271459913267735850L;

		private MatrixBlock extractBlock(Iterable<MatrixBlock> blks, MatrixBlock retVal) throws Exception {
			for(MatrixBlock blk1 : blks) {
				if(retVal != null) {
					throw new Exception("ERROR: More than 1 matrixblock found for one of the inputs at a given index");
				}
				retVal = blk1;
			}
			if(retVal == null) {
				throw new Exception("ERROR: No matrixblock found for one of the inputs at a given index");
			}
			return retVal;
		}
		
		@Override
		public Tuple2<MatrixIndexes, ArrayList<MatrixBlock>> call(
				Tuple2<MatrixIndexes, Tuple2<Iterable<MatrixBlock>, Iterable<MatrixBlock>>> kv)
				throws Exception {
			MatrixBlock in1 = null; MatrixBlock in2 = null;
			in1 = extractBlock(kv._2._1, in1);
			in2 = extractBlock(kv._2._2, in2);
			// Now return unflatten AL
			ArrayList<MatrixBlock> inputs = new ArrayList<MatrixBlock>();
			inputs.add(in1); inputs.add(in2);  
			return new Tuple2<MatrixIndexes, ArrayList<MatrixBlock>>(kv._1, inputs);
		}
		
	}
	
	public static class MapThreeMBIterableIntoAL implements PairFunction<Tuple2<MatrixIndexes,Tuple2<Iterable<Tuple2<Iterable<MatrixBlock>,Iterable<MatrixBlock>>>,Iterable<MatrixBlock>>>, MatrixIndexes, ArrayList<MatrixBlock>> {

		private static final long serialVersionUID = -4873754507037646974L;
		
		private MatrixBlock extractBlock(Iterable<MatrixBlock> blks, MatrixBlock retVal) throws Exception {
			for(MatrixBlock blk1 : blks) {
				if(retVal != null) {
					throw new Exception("ERROR: More than 1 matrixblock found for one of the inputs at a given index");
				}
				retVal = blk1;
			}
			if(retVal == null) {
				throw new Exception("ERROR: No matrixblock found for one of the inputs at a given index");
			}
			return retVal;
		}

		@Override
		public Tuple2<MatrixIndexes, ArrayList<MatrixBlock>> call(
				Tuple2<MatrixIndexes, Tuple2<Iterable<Tuple2<Iterable<MatrixBlock>, Iterable<MatrixBlock>>>, Iterable<MatrixBlock>>> kv)
				throws Exception {
			MatrixBlock in1 = null; MatrixBlock in2 = null; MatrixBlock in3 = null;
			
			for(Tuple2<Iterable<MatrixBlock>, Iterable<MatrixBlock>> blks : kv._2._1) {
				in1 = extractBlock(blks._1, in1);
				in2 = extractBlock(blks._2, in2);
			}
			in3 = extractBlock(kv._2._2, in3);
			
			// Now return unflatten AL
			ArrayList<MatrixBlock> inputs = new ArrayList<MatrixBlock>();
			inputs.add(in1); inputs.add(in2); inputs.add(in3);  
			return new Tuple2<MatrixIndexes, ArrayList<MatrixBlock>>(kv._1, inputs);
		}
		
	}
	
	public static class PerformCTableMapSideOperation implements PairFunction<Tuple2<MatrixIndexes,ArrayList<MatrixBlock>>, MatrixIndexes, CTableMap> {

		private static final long serialVersionUID = 5348127596473232337L;

		Ternary.OperationTypes ctableOp;
		double scalar_input2; double scalar_input3;
		String instString; int  brlen;
		Operator optr;
		boolean ignoreZeros;
		
		public PerformCTableMapSideOperation(Ternary.OperationTypes ctableOp, double scalar_input2, double scalar_input3, int brlen, String instString, Operator optr, boolean ignoreZeros) {
			this.ctableOp = ctableOp;
			this.scalar_input2 = scalar_input2;
			this.scalar_input3 = scalar_input3;
			this.instString = instString;
			this.brlen = brlen;
			this.optr = optr;
			this.ignoreZeros = ignoreZeros;
		}
		
		private void expectedALSize(int length, ArrayList<MatrixBlock> al) throws Exception {
			if(al.size() != length) {
				throw new Exception("Expected arraylist of size:" + length + ", but found " + al.size());
			}
		}
		
		@Override
		public Tuple2<MatrixIndexes, CTableMap> call(
				Tuple2<MatrixIndexes, ArrayList<MatrixBlock>> kv) throws Exception {
			CTableMap ctableResult = new CTableMap();
			MatrixBlock ctableResultBlock = null;
			
			IndexedMatrixValue in1, in2, in3 = null;
			in1 = new IndexedMatrixValue(kv._1, kv._2.get(0));
			MatrixBlock matBlock1 = kv._2.get(0);
			
			switch( ctableOp )
			{
				case CTABLE_TRANSFORM: {
					in2 = new IndexedMatrixValue(kv._1, kv._2.get(1));
					in3 = new IndexedMatrixValue(kv._1, kv._2.get(2));
					expectedALSize(3, kv._2);
					
					if(in1==null || in2==null || in3 == null )
						break;	
					else
						OperationsOnMatrixValues.performTernary(in1.getIndexes(), in1.getValue(), in2.getIndexes(), in2.getValue(), 
                                in3.getIndexes(), in3.getValue(), ctableResult, ctableResultBlock, optr);
					break;
				}
				case CTABLE_TRANSFORM_SCALAR_WEIGHT: 
				case CTABLE_EXPAND_SCALAR_WEIGHT:
				{
					// 3rd input is a scalar
					in2 = new IndexedMatrixValue(kv._1, kv._2.get(1));
					expectedALSize(2, kv._2);
					if(in1==null || in2==null )
						break;
					else
						matBlock1.ternaryOperations((SimpleOperator)optr, kv._2.get(1), scalar_input3, ignoreZeros, ctableResult, ctableResultBlock);
						break;
				}
				case CTABLE_TRANSFORM_HISTOGRAM: {
					expectedALSize(1, kv._2);
					OperationsOnMatrixValues.performTernary(in1.getIndexes(), in1.getValue(), scalar_input2, 
							scalar_input3, ctableResult, ctableResultBlock, optr);
					break;
				}
				case CTABLE_TRANSFORM_WEIGHTED_HISTOGRAM: {
					// 2nd and 3rd inputs are scalars
					expectedALSize(2, kv._2);
					in3 = new IndexedMatrixValue(kv._1, kv._2.get(1)); // Note: kv._2.get(1), not kv._2.get(2)
					
					if(in1==null || in3==null)
						break;
					else
						OperationsOnMatrixValues.performTernary(in1.getIndexes(), in1.getValue(), scalar_input2, 
								in3.getIndexes(), in3.getValue(), ctableResult, ctableResultBlock, optr);		
					break;
				}
				default:
					throw new DMLRuntimeException("Unrecognized opcode in Tertiary Instruction: " + instString);		
			}
			
			return new Tuple2<MatrixIndexes, CTableMap>(kv._1, ctableResult);
		}
		
	}
	
	public static class MapMBIntoAL implements PairFunction<Tuple2<MatrixIndexes, MatrixBlock>, MatrixIndexes, ArrayList<MatrixBlock>> {

		private static final long serialVersionUID = 2068398913653350125L;

		@Override
		public Tuple2<MatrixIndexes, ArrayList<MatrixBlock>> call(
				Tuple2<MatrixIndexes, MatrixBlock> kv) throws Exception {
			ArrayList<MatrixBlock> retVal = new ArrayList<MatrixBlock>();
			retVal.add(kv._2);
			return new Tuple2<MatrixIndexes, ArrayList<MatrixBlock>>(kv._1, retVal);
		}
		
	}
	
	public static class ExtractBinaryCellsFromCTable implements PairFlatMapFunction<CTableMap, MatrixIndexes, Double> {

		private static final long serialVersionUID = -5933677686766674444L;

		int brlen; int bclen;
		public ExtractBinaryCellsFromCTable(int brlen, int bclen) {
			this.brlen = brlen;
			this.bclen = bclen;
		}
		
		@SuppressWarnings("deprecation")
		@Override
		public Iterable<Tuple2<MatrixIndexes, Double>> call(CTableMap ctableMap)
				throws Exception {
			ArrayList<Tuple2<MatrixIndexes, Double>> retVal = new ArrayList<Tuple2<MatrixIndexes, Double>>();
			
			for(Entry<MatrixIndexes, Double> ijv : ctableMap.entrySet()) {
				long i = ijv.getKey().getRowIndex();
				long j =  ijv.getKey().getColumnIndex();
				double v =  ijv.getValue();
				
				// retVal.add(new Tuple2<MatrixIndexes, MatrixCell>(blockIndexes, cell));
				retVal.add(new Tuple2<MatrixIndexes, Double>(new MatrixIndexes(i, j), v));
			}
			return retVal;
		}
		
	}
	
	public static class FindOutputDimensions implements Function2<MatrixIndexes, MatrixIndexes, MatrixIndexes> {
		private static final long serialVersionUID = -8421979264801112485L;

		@Override
		public MatrixIndexes call(MatrixIndexes left, MatrixIndexes right) throws Exception {
			return new MatrixIndexes(Math.max(left.getRowIndex(), right.getRowIndex()), Math.max(left.getColumnIndex(), right.getColumnIndex()));
		}
		
	}
	
	public static class ConvertToBinaryCell implements PairFunction<Tuple2<MatrixIndexes,Double>, MatrixIndexes, MatrixCell> {

		private static final long serialVersionUID = 7481186480851982800L;
		
		int brlen; int bclen;
		long rlen; long clen;
		public ConvertToBinaryCell(int brlen, int bclen, long rlen, long clen) {
			this.brlen = brlen;
			this.bclen = bclen;
			this.rlen = rlen;
			this.clen = clen;
		}

		@Override
		public Tuple2<MatrixIndexes, MatrixCell> call(
				Tuple2<MatrixIndexes, Double> kv) throws Exception {
			long i = kv._1.getRowIndex();
			long j = kv._1.getColumnIndex();
			double v = kv._2;
			if(i > rlen || j > clen) {
				throw new Exception("Incorrect input in ConvertToBinaryCell: (" + i + " " + j + " " + v + ")");
			}
			// ------------------------------------------------------------------------------------------
			// Get appropriate indexes for blockIndexes and cell
			// For line: 1020 704 2.362153706180234 (assuming default block size: 1000 X 1000),
			// blockRowIndex = 2, blockColIndex = 1, rowIndexInBlock = 19, colIndexInBlock = 703 
			long blockRowIndex = UtilFunctions.blockIndexCalculation(i, (int) brlen);
			long blockColIndex = UtilFunctions.blockIndexCalculation(j, (int) bclen);
			long rowIndexInBlock = UtilFunctions.cellInBlockCalculation(i, brlen);
			long colIndexInBlock = UtilFunctions.cellInBlockCalculation(j, bclen);
			// Perform sanity check
			if(blockRowIndex <= 0 || blockColIndex <= 0 || rowIndexInBlock < 0 || colIndexInBlock < 0) {
				throw new Exception("Error computing indexes for (:" + i + ", " + j + "," + v + ")");
			}
			// ------------------------------------------------------------------------------------------
			
			MatrixIndexes blockIndexes = new MatrixIndexes(blockRowIndex, blockColIndex);
			MatrixCell cell = new MatrixCell(rowIndexInBlock, colIndexInBlock, v);
			return new Tuple2<MatrixIndexes, MatrixCell>(blockIndexes, cell);
		}
		
	}
	
	public static class AggregateCells implements Function2<Double, Double, Double> {
		private static final long serialVersionUID = -8167625566734873796L;

		@Override
		public Double call(Double v1, Double v2) throws Exception {
			return v1 + v2;
		}
		
	}
	
	public static class FilterCells implements Function<Tuple2<MatrixIndexes,Double>, Boolean> {
		private static final long serialVersionUID = 108448577697623247L;

		long rlen; long clen;
		public FilterCells(long rlen, long clen) {
			this.rlen = rlen;
			this.clen = clen;
		}
		
		@Override
		public Boolean call(Tuple2<MatrixIndexes, Double> kv) throws Exception {
			if(kv._1.getRowIndex() <= 0 || kv._1.getColumnIndex() <= 0) {
				throw new Exception("Incorrect cell values in TernarySPInstruction:" + kv._1);
			}
			if(kv._1.getRowIndex() <= rlen && kv._1.getColumnIndex() <= clen) {
				return true;
			}
			return false;
		}
		
	}
	
	@Override
	public void processInstruction(ExecutionContext ec) 
		throws DMLRuntimeException, DMLUnsupportedOperationException {
		
		SparkExecutionContext sec = (SparkExecutionContext)ec;
		//get input rdd handle
		JavaPairRDD<MatrixIndexes,MatrixBlock> in1 = sec.getBinaryBlockRDDHandleForVariable( input1.getName() );
		
		JavaPairRDD<MatrixIndexes,MatrixBlock> in2 = null;
		JavaPairRDD<MatrixIndexes,MatrixBlock> in3 = null;
		double scalar_input2 = -1, scalar_input3 = -1;
		
		Ternary.OperationTypes ctableOp = findCtableOperation();
		ctableOp = _isExpand ? Ternary.OperationTypes.CTABLE_EXPAND_SCALAR_WEIGHT : ctableOp;
		
		MatrixCharacteristics mc1 = sec.getMatrixCharacteristics(input1.getName());
		MatrixCharacteristics mcOut = sec.getMatrixCharacteristics(output.getName());
		if(!mcOut.dimsKnown()) {
			if(!mc1.dimsKnown()) {
				throw new DMLRuntimeException("The output dimensions are not specified for TernarySPInstruction");
			}
			else {
				mcOut.set(mc1.getCols(), mc1.getRows(), mc1.getColsPerBlock(), mc1.getRowsPerBlock());
			}
		}
		int brlen = mcOut.getRowsPerBlock();
		int bclen = mcOut.getColsPerBlock();
		
		JavaPairRDD<MatrixIndexes, ArrayList<MatrixBlock>> inputMBs = null;
		JavaPairRDD<MatrixIndexes, CTableMap> ctables = null;
		
		boolean setLineage2 = false;
		boolean setLineage3 = false;
		switch(ctableOp) {
			case CTABLE_TRANSFORM: //(VECTOR)
				// F=ctable(A,B,W) 
				in2 = sec.getBinaryBlockRDDHandleForVariable( input2.getName() );
				in3 = sec.getBinaryBlockRDDHandleForVariable( input3.getName() );
				setLineage2 = true;
				setLineage3 = true;
				
				inputMBs = in1.cogroup(in2).cogroup(in3)
							.mapToPair(new MapThreeMBIterableIntoAL());
				
				ctables = inputMBs.mapToPair(new PerformCTableMapSideOperation(ctableOp, scalar_input2, 
							scalar_input3, brlen, this.instString, (SimpleOperator)_optr, _ignoreZeros));
				break;
				
			case CTABLE_TRANSFORM_SCALAR_WEIGHT: //(VECTOR/MATRIX)
				// F = ctable(A,B) or F = ctable(A,B,1)
			case CTABLE_EXPAND_SCALAR_WEIGHT: //(VECTOR)
				// F = ctable(seq,A) or F = ctable(seq,B,1)
				in2 = sec.getBinaryBlockRDDHandleForVariable( input2.getName() );
				setLineage2 = true;

				scalar_input3 = sec.getScalarInput(input3.getName(), input3.getValueType(), input3.isLiteral()).getDoubleValue();
				inputMBs = in1.cogroup(in2).mapToPair(new MapTwoMBIterableIntoAL());
				
				ctables = inputMBs.mapToPair(new PerformCTableMapSideOperation(ctableOp, scalar_input2, 
						scalar_input3, brlen, this.instString, (SimpleOperator)_optr, _ignoreZeros));
				break;
				
			case CTABLE_TRANSFORM_HISTOGRAM: //(VECTOR)
				// F=ctable(A,1) or F = ctable(A,1,1)
				scalar_input2 = sec.getScalarInput(input2.getName(), input2.getValueType(), input2.isLiteral()).getDoubleValue();
				scalar_input3 = sec.getScalarInput(input3.getName(), input3.getValueType(), input3.isLiteral()).getDoubleValue();
				inputMBs = in1.mapToPair(new MapMBIntoAL());
				
				ctables = inputMBs.mapToPair(new PerformCTableMapSideOperation(ctableOp, scalar_input2, 
						scalar_input3, brlen, this.instString, (SimpleOperator)_optr, _ignoreZeros));
				break;
				
			case CTABLE_TRANSFORM_WEIGHTED_HISTOGRAM: //(VECTOR)
				// F=ctable(A,1,W)
				in3 = sec.getBinaryBlockRDDHandleForVariable( input3.getName() );
				setLineage3 = true;
				
				scalar_input2 = sec.getScalarInput(input2.getName(), input2.getValueType(), input2.isLiteral()).getDoubleValue();
				inputMBs = in1.cogroup(in3).mapToPair(new MapTwoMBIterableIntoAL());
				
				ctables = inputMBs.mapToPair(new PerformCTableMapSideOperation(ctableOp, scalar_input2, 
						scalar_input3, brlen, this.instString, (SimpleOperator)_optr, _ignoreZeros));
				break;
			
			default:
				throw new DMLRuntimeException("Encountered an invalid ctable operation ("+ctableOp+") while executing instruction: " + this.toString());
		}
		
		// Now perform aggregation on ctables to get binaryCells 
		JavaPairRDD<MatrixIndexes, Double> binaryCellsWithoutFilter =  
				ctables.values()
				.flatMapToPair(new ExtractBinaryCellsFromCTable(brlen, bclen))
				.reduceByKey(new AggregateCells());
		
		// For filtering, we need to know the dimensions
		// So, compute dimension if necessary
		long outputDim1 = (_dim1Literal ? (long) Double.parseDouble(_outDim1) : (sec.getScalarInput(_outDim1, ValueType.DOUBLE, false)).getLongValue());
		long outputDim2 = (_dim2Literal ? (long) Double.parseDouble(_outDim2) : (sec.getScalarInput(_outDim2, ValueType.DOUBLE, false)).getLongValue());
		MatrixCharacteristics mcBinaryCells = null;
		boolean findDimensions = (outputDim1 == -1 && outputDim2 == -1); 
		if(findDimensions) {
			binaryCellsWithoutFilter = binaryCellsWithoutFilter.persist(StorageLevel.MEMORY_AND_DISK());
			MatrixIndexes dims = binaryCellsWithoutFilter.keys().reduce(new FindOutputDimensions());
			mcBinaryCells = new MatrixCharacteristics(dims.getRowIndex(), dims.getColumnIndex(), brlen, bclen);
		}
		else if((outputDim1 == -1 && outputDim2 != -1) || (outputDim1 != -1 && outputDim2 == -1)) {
			throw new DMLRuntimeException("Incorrect output dimensions passed to TernarySPInstruction:" + outputDim1 + " " + outputDim2);
		}
		else 
			mcBinaryCells = new MatrixCharacteristics(outputDim1, outputDim2, brlen, bclen);
		
		// Now that dimensions are computed, do filtering
		JavaPairRDD<MatrixIndexes, Double> binaryCellsAfterFilter =
				binaryCellsWithoutFilter.filter(new FilterCells(mcBinaryCells.getRows(), mcBinaryCells.getCols()));
		if(findDimensions) 
			binaryCellsWithoutFilter = binaryCellsWithoutFilter.unpersist();	
		
		// Convert value 'Double' to 'MatrixCell'
		JavaPairRDD<MatrixIndexes, MatrixCell> binaryCells = 
				binaryCellsAfterFilter
				.mapToPair(new ConvertToBinaryCell(brlen, bclen, mcBinaryCells.getRows(), mcBinaryCells.getCols()));
				
		
		// Now, reblock the binary cells into MatrixBlock
		// TODO: Add reblock rewrite and remove this part of code
		boolean outputEmptyBlocks = true;
		JavaPairRDD<MatrixIndexes,MatrixBlock> out = 
				ReblockSPInstruction.processBinaryCellReblock(sec, binaryCells, 
				mcBinaryCells, mcBinaryCells, outputEmptyBlocks, brlen, bclen);
		
		//store output rdd handle
		sec.setRDDHandleForVariable(output.getName(), out);
		mcOut.set(mcBinaryCells);
		sec.addLineageRDD(output.getName(), input1.getName());
		if(setLineage2)
			sec.addLineageRDD(output.getName(), input2.getName());
		if(setLineage3)
			sec.addLineageRDD(output.getName(), input3.getName());
	}	
}