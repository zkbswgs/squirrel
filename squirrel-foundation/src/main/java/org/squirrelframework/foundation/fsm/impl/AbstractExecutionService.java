package org.squirrelframework.foundation.fsm.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Stack;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.squirrelframework.foundation.component.impl.AbstractSubject;
import org.squirrelframework.foundation.exception.ErrorCodes;
import org.squirrelframework.foundation.exception.SquirrelRuntimeException;
import org.squirrelframework.foundation.exception.TransitionException;
import org.squirrelframework.foundation.fsm.Action;
import org.squirrelframework.foundation.fsm.ActionExecutionService;
import org.squirrelframework.foundation.fsm.StateMachine;

import com.google.common.base.Preconditions;

public abstract class AbstractExecutionService<T extends StateMachine<T, S, E, C>, S, E, C> 
    extends AbstractSubject implements ActionExecutionService<T, S, E, C> {
    
    private static final Logger logger = LoggerFactory.getLogger(AbstractExecutionService.class);

    protected final Stack<List<ActionContext<T, S, E, C>>> stack = new Stack<List<ActionContext<T, S, E, C>>>();
    
    protected boolean dummyExecution = false;
    
    @Override
    public void begin() {
        List<ActionContext<T, S, E, C>> actionContext = new ArrayList<ActionContext<T, S, E, C>>();
        stack.push(actionContext);
    }
    
    @Override
    public void defer(Action<T, S, E, C> action, S from, S to, E event, C context, T stateMachine) {
        Preconditions.checkNotNull(action);
        stack.peek().add(ActionContext.get(action, from, to, event, context, stateMachine));
    }
    
    @Override
    public void execute() {
        if(dummyExecution) return;
        
        List<ActionContext<T, S, E, C>> actionContexts = stack.pop();
        for (int i=0, size=actionContexts.size(); i<size; ++i) {
            ActionContext<T, S, E, C> actionContext = actionContexts.get(i);
            if(actionContext.action.weight()!=Action.IGNORE_WEIGHT) {
                try {
                    fireEvent(BeforeExecActionEventImpl.get(i+1, size, actionContext));
                    actionContext.run();
                } catch (Exception e) {
                    Throwable t = (e instanceof SquirrelRuntimeException) ?
                            ((SquirrelRuntimeException)e).getTargetException() : e;
                    // wrap any exception into transition exception
                    TransitionException te = new TransitionException(t, ErrorCodes.FSM_TRANSITION_ERROR, 
                            new Object[]{actionContext.from, actionContext.to, actionContext.event, 
                            actionContext.context, actionContext.action.name(), e.getMessage()});
                    fireEvent(new ExecActionExceptionEventImpl<T, S, E, C>(te, i+1, size, actionContext));
                    throw te;
                } finally {
                    fireEvent(AfterExecActionEventImpl.get(i+1, size, actionContext));
                }
            } else {
                logger.info("Method call action \""+actionContext.action.name()+"\" ("+(i+1)+" of "+size+") was ignored.");
            }
        }
    }
    
    @Override
    public void addExecActionListener(BeforeExecActionListener<T, S, E, C> listener) {
        addListener(BeforeExecActionEvent.class, listener, BeforeExecActionListener.METHOD);
    }
    
    @Override
    public void removeExecActionListener(BeforeExecActionListener<T, S, E, C> listener) {
        removeListener(BeforeExecActionEvent.class, listener);
    }
    
    @Override
    public void addExecActionListener(AfterExecActionListener<T, S, E, C> listener) {
        addListener(AfterExecActionListener.class, listener, AfterExecActionListener.METHOD);
    }
    
    @Override
    public void removeExecActionListener(AfterExecActionListener<T, S, E, C> listener) {
        removeListener(AfterExecActionListener.class, listener);
    }
    
    @Override
    public void addExecActionExceptionListener(ExecActionExceptionListener<T, S, E, C> listener) {
        addListener(ExecActionExceptionEvent.class, listener, ExecActionExceptionListener.METHOD);
    }
    
    @Override
    public void removeExecActionExceptionListener(ExecActionExceptionListener<T, S, E, C> listener) {
        removeListener(ExecActionExceptionEvent.class, listener);
    }
    
    @Override
    public void setDummyExecution(boolean dummyExecution) {
        this.dummyExecution = dummyExecution;
    }
    
    static class ExecActionExceptionEventImpl<T extends StateMachine<T, S, E, C>, S, E, C> 
        extends AbstractExecActionEvent<T, S, E, C> implements ExecActionExceptionEvent<T, S, E, C> {
        
        private final TransitionException e;

        ExecActionExceptionEventImpl(TransitionException e, int pos, int size, ActionContext<T, S, E, C> actionContext) {
            super(pos, size, actionContext);
            this.e = e;
        }

        @Override
        public TransitionException getException() {
            return e;
        }
        
    }
    
    static class BeforeExecActionEventImpl<T extends StateMachine<T, S, E, C>, S, E, C> 
            extends AbstractExecActionEvent<T, S, E, C> implements BeforeExecActionEvent<T, S, E, C> {
        
        BeforeExecActionEventImpl(int pos, int size, ActionContext<T, S, E, C> actionContext) {
            super(pos, size, actionContext);
        }

        static <T extends StateMachine<T, S, E, C>, S, E, C> BeforeExecActionEvent<T, S, E, C> get(
                int pos, int size, ActionContext<T, S, E, C> actionContext) {
            return new BeforeExecActionEventImpl<T, S, E, C>(pos, size, actionContext);
        }
    }
    
    static class AfterExecActionEventImpl<T extends StateMachine<T, S, E, C>, S, E, C>
            extends AbstractExecActionEvent<T, S, E, C> implements AfterExecActionEvent<T, S, E, C> {

        AfterExecActionEventImpl(int pos, int size, ActionContext<T, S, E, C> actionContext) {
            super(pos, size, actionContext);
        }

        static <T extends StateMachine<T, S, E, C>, S, E, C> AfterExecActionEvent<T, S, E, C> get(
                int pos, int size, ActionContext<T, S, E, C> actionContext) {
            return new AfterExecActionEventImpl<T, S, E, C>(pos, size, actionContext);
        }
    }
    
    static abstract class AbstractExecActionEvent<T extends StateMachine<T, S, E, C>, S, E, C> 
            implements ActionEvent<T, S, E, C> {
        private ActionContext<T, S, E, C> executionContext;
        private int pos;
        private int size;
        
        AbstractExecActionEvent(int pos, int size, ActionContext<T, S, E, C> actionContext) {
            this.pos = pos;
            this.size = size;
            this.executionContext = actionContext;
        }
        
        @Override
        public Action<T, S, E, C> getExecutionTarget() {
            // user can only read action info but cannot invoke action in the listener method
            return new UncallableActionImpl<T, S, E, C>(executionContext.action);
        }

        @Override
        public S getFrom() {
            return executionContext.from;
        }

        @Override
        public S getTo() {
            return executionContext.to;
        }

        @Override
        public E getEvent() {
            return executionContext.event;
        }

        @Override
        public C getContext() {
            return executionContext.context;
        }

        @Override
        public T getStateMachine() {
            return executionContext.fsm;
        }

        @Override
        public int[] getMOfN() {
            return new int[]{pos, size};
        }
    }
    
    static class ActionContext<T extends StateMachine<T, S, E, C>, S, E, C> {
        final Action<T, S, E, C> action;
        final S from;
        final S to;
        final E event;
        final C context;
        final T fsm;
        
        private ActionContext(Action<T, S, E, C> action, S from, S to, E event, C context, T stateMachine) {
            this.action = action;
            this.from = from;
            this.to = to;
            this.event = event;
            this.context = context;
            this.fsm = stateMachine;
        }
        
        static <T extends StateMachine<T, S, E, C>, S, E, C> ActionContext<T, S, E, C> get(
                Action<T, S, E, C> action, S from, S to, E event, C context, T stateMachine) {
            return new ActionContext<T, S, E, C>(action, from, to, event, context, stateMachine);
        }

        void run() {
            AbstractStateMachine<T, S, E, C> fsmImpl = (AbstractStateMachine<T, S, E, C>)fsm;
            fsmImpl.beforeActionInvoked(from, to, event, context);
            action.execute(from, to, event, context, fsm);
            fsmImpl.afterActionInvoked(from, to, event, context);
        }
    }
}
