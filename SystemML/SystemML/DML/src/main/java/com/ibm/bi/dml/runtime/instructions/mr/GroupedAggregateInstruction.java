/**
 * IBM Confidential
 * OCO Source Materials
 * (C) Copyright IBM Corp. 2010, 2015
 * The source code for this program is not published or otherwise divested of its trade secrets, irrespective of what has been deposited with the U.S. Copyright Office.
 */

package com.ibm.bi.dml.runtime.instructions.mr;

import com.ibm.bi.dml.lops.PartialAggregate.CorrectionLocationType;
import com.ibm.bi.dml.runtime.DMLRuntimeException;
import com.ibm.bi.dml.runtime.DMLUnsupportedOperationException;
import com.ibm.bi.dml.runtime.functionobjects.CM;
import com.ibm.bi.dml.runtime.functionobjects.KahanPlus;
import com.ibm.bi.dml.runtime.instructions.Instruction;
import com.ibm.bi.dml.runtime.instructions.InstructionUtils;
import com.ibm.bi.dml.runtime.matrix.data.MatrixValue;
import com.ibm.bi.dml.runtime.matrix.mapred.CachedValueMap;
import com.ibm.bi.dml.runtime.matrix.mapred.IndexedMatrixValue;
import com.ibm.bi.dml.runtime.matrix.operators.AggregateOperator;
import com.ibm.bi.dml.runtime.matrix.operators.CMOperator;
import com.ibm.bi.dml.runtime.matrix.operators.Operator;
import com.ibm.bi.dml.runtime.matrix.operators.CMOperator.AggregateOperationTypes;


public class GroupedAggregateInstruction extends UnaryMRInstructionBase
{
	@SuppressWarnings("unused")
	private static final String _COPYRIGHT = "Licensed Materials - Property of IBM\n(C) Copyright IBM Corp. 2010, 2015\n" +
                                             "US Government Users Restricted Rights - Use, duplication  disclosure restricted by GSA ADP Schedule Contract with IBM Corp.";
	
	
	public GroupedAggregateInstruction(Operator op, byte in, byte out, String istr) {
		super(op, in, out);
		mrtype = MRINSTRUCTION_TYPE.GroupedAggregate;
		instString = istr;
	}

	@Override
	public void processInstruction(Class<? extends MatrixValue> valueClass,
			CachedValueMap cachedValues, IndexedMatrixValue tempValue,
			IndexedMatrixValue zeroInput,
			int blockRowFactor, int blockColFactor)
			throws DMLUnsupportedOperationException, DMLRuntimeException {
		throw new DMLRuntimeException("GroupedAggregateInstruction.processInstruction() should not be called!");
		
	}

	public static Instruction parseInstruction ( String str ) throws DMLRuntimeException {
		
		String[] parts = InstructionUtils.getInstructionParts ( str );
		if(parts.length<2)
			throw new DMLRuntimeException("the number of fields of instruction "+str+" is less than 2!");
		byte in, out;
		String opcode = parts[0];
		in = Byte.parseByte(parts[1]);
		out = Byte.parseByte(parts[parts.length - 1]);
		
		if ( !opcode.equalsIgnoreCase("groupedagg") ) {
			throw new DMLRuntimeException("Invalid opcode in GroupedAggregateInstruction: " + opcode);
		}
		
		Operator optr = parseGroupedAggOperator(parts[2], parts[3]);
		return new GroupedAggregateInstruction(optr, in, out, str);
	}
	
	public static Operator parseGroupedAggOperator(String fn, String other) throws DMLRuntimeException {
		AggregateOperationTypes op = AggregateOperationTypes.INVALID;
		if ( fn.equalsIgnoreCase("centralmoment") )
			// in case of CM, we also need to pass "order"
			op = CMOperator.getAggOpType(fn, other);
		else 
			op = CMOperator.getAggOpType(fn, null);
	
		switch(op) {
		case SUM:
			return new AggregateOperator(0, KahanPlus.getKahanPlusFnObject(), true, CorrectionLocationType.LASTCOLUMN);
			
		case COUNT:
		case MEAN:
		case VARIANCE:
		case CM2:
		case CM3:
		case CM4:
			return new CMOperator(CM.getCMFnObject(op), op);
		case INVALID:
		default:
			throw new DMLRuntimeException("Invalid Aggregate Operation in GroupedAggregateInstruction: " + op);
		}
	}

}