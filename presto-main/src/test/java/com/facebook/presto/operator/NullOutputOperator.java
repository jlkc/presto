package com.facebook.presto.noperator;

import com.facebook.presto.operator.Page;
import com.facebook.presto.tuple.TupleInfo;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.List;

import static com.google.common.base.Preconditions.checkNotNull;

public class NullOutputOperator
        implements NewOperator
{
    public static class NullOutputFactory
            implements OutputFactory
    {
        @Override
        public NewOperatorFactory createOutputOperator(int operatorId, List<TupleInfo> sourceTupleInfo)
        {
            return new NullOutputOperatorFactory(operatorId, sourceTupleInfo);
        }
    }

    public static class NullOutputOperatorFactory
            implements NewOperatorFactory
    {
        private final int operatorId;
        private final List<TupleInfo> tupleInfos;

        public NullOutputOperatorFactory(int operatorId, List<TupleInfo> tupleInfos)
        {
            this.operatorId = operatorId;
            this.tupleInfos = tupleInfos;
        }

        @Override
        public List<TupleInfo> getTupleInfos()
        {
            return tupleInfos;
        }

        @Override
        public NewOperator createOperator(DriverContext driverContext)
        {
            OperatorContext operatorContext = driverContext.addOperatorContext(operatorId, NullOutputOperator.class.getSimpleName());
            return new NullOutputOperator(operatorContext, tupleInfos);
        }

        @Override
        public void close()
        {
        }
    }

    private final OperatorContext operatorContext;
    private final List<TupleInfo> tupleInfos;
    private boolean finished;

    public NullOutputOperator(OperatorContext operatorContext, List<TupleInfo> tupleInfos)
    {
        this.operatorContext = checkNotNull(operatorContext, "operatorContext is null");
        this.tupleInfos = ImmutableList.copyOf(checkNotNull(tupleInfos, "tupleInfos is null"));
    }

    @Override
    public OperatorContext getOperatorContext()
    {
        return operatorContext;
    }

    @Override
    public List<TupleInfo> getTupleInfos()
    {
        return tupleInfos;
    }

    @Override
    public void finish()
    {
        finished = true;
    }

    @Override
    public boolean isFinished()
    {
        return finished;
    }

    @Override
    public ListenableFuture<?> isBlocked()
    {
        return NOT_BLOCKED;
    }

    @Override
    public boolean needsInput()
    {
        return true;
    }

    @Override
    public void addInput(Page page)
    {
        operatorContext.recordGeneratedOutput(page.getDataSize(), page.getPositionCount());
    }

    @Override
    public Page getOutput()
    {
        return null;
    }
}