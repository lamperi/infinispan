package org.infinispan.interceptors.impl;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import org.infinispan.commands.FlagAffectedCommand;
import org.infinispan.commands.tx.PrepareCommand;
import org.infinispan.context.Flag;
import org.infinispan.context.impl.LocalTxInvocationContext;
import org.infinispan.context.impl.TxInvocationContext;
import org.infinispan.factories.annotations.Inject;
import org.infinispan.factories.annotations.Start;
import org.infinispan.interceptors.DDAsyncInterceptor;
import org.infinispan.remoting.inboundhandler.DeliverOrder;
import org.infinispan.remoting.responses.Response;
import org.infinispan.remoting.responses.SelfDeliverFilter;
import org.infinispan.remoting.responses.TimeoutValidationResponseFilter;
import org.infinispan.remoting.rpc.ResponseMode;
import org.infinispan.remoting.rpc.RpcManager;
import org.infinispan.remoting.rpc.RpcOptions;
import org.infinispan.remoting.rpc.RpcOptionsBuilder;
import org.infinispan.remoting.transport.Address;
import org.infinispan.transaction.impl.LocalTransaction;
import org.infinispan.util.concurrent.CompletableFutures;
import org.infinispan.util.logging.Log;

/**
 * Acts as a base for all RPC calls
 *
 * @author <a href="mailto:manik@jboss.org">Manik Surtani (manik@jboss.org)</a>
 * @author Mircea.Markus@jboss.com
 * @since 9.0
 */
public abstract class BaseRpcInterceptor extends DDAsyncInterceptor {
   protected boolean trace = getLog().isTraceEnabled();

   protected RpcManager rpcManager;

   protected boolean defaultSynchronous;
   protected RpcOptions staggeredOptions;
   protected RpcOptions defaultSyncOptions;

   protected abstract Log getLog();

   @Inject
   public void inject(RpcManager rpcManager) {
      this.rpcManager = rpcManager;
   }

   @Start
   public void init() {
      defaultSynchronous = cacheConfiguration.clustering().cacheMode().isSynchronous();
      staggeredOptions = rpcManager.getRpcOptionsBuilder(ResponseMode.WAIT_FOR_VALID_RESPONSE, DeliverOrder.NONE).build();
      defaultSyncOptions = rpcManager.getDefaultRpcOptions(true);
   }

   protected final boolean isSynchronous(FlagAffectedCommand command) {
      if (command.hasFlag(Flag.FORCE_SYNCHRONOUS))
         return true;
      else if (command.hasFlag(Flag.FORCE_ASYNCHRONOUS))
         return false;

      return defaultSynchronous;
   }

   protected final boolean isLocalModeForced(FlagAffectedCommand command) {
      if (command.hasFlag(Flag.CACHE_MODE_LOCAL)) {
         if (trace) getLog().trace("LOCAL mode forced on invocation.  Suppressing clustered events.");
         return true;
      }
      return false;
   }

   protected boolean shouldInvokeRemoteTxCommand(TxInvocationContext ctx) {
      if (!ctx.isOriginLocal()) {
         return false;
      }

      // Skip the remote invocation if this is a state transfer transaction
      LocalTxInvocationContext localCtx = (LocalTxInvocationContext) ctx;
      if (localCtx.getCacheTransaction().getStateTransferFlag() == Flag.PUT_FOR_STATE_TRANSFER) {
         return false;
      }

      // just testing for empty modifications isn't enough - the Lock API may acquire locks on keys but won't
      // register a Modification.  See ISPN-711.
      boolean shouldInvokeRemotely = ctx.hasModifications() || !localCtx.getRemoteLocksAcquired().isEmpty() ||
         localCtx.getCacheTransaction().getTopologyId() != rpcManager.getTopologyId();

      if (trace) {
         getLog().tracef("Should invoke remotely? %b. hasModifications=%b, hasRemoteLocksAcquired=%b",
               shouldInvokeRemotely, ctx.hasModifications(), !localCtx.getRemoteLocksAcquired().isEmpty());
      }

      return shouldInvokeRemotely;
   }

   protected static void transactionRemotelyPrepared(TxInvocationContext ctx) {
      if (ctx.isOriginLocal()) {
         ((LocalTransaction)ctx.getCacheTransaction()).markPrepareSent();
      }
   }

   protected static void totalOrderTxCommit(TxInvocationContext ctx) {
      if (ctx.isOriginLocal()) {
         ((LocalTransaction)ctx.getCacheTransaction()).markCommitOrRollbackSent();
      }
   }

   protected static void totalOrderTxRollback(TxInvocationContext ctx) {
      if (ctx.isOriginLocal()) {
         ((LocalTransaction)ctx.getCacheTransaction()).markCommitOrRollbackSent();
      }
   }

   protected static boolean shouldTotalOrderRollbackBeInvokedRemotely(TxInvocationContext ctx) {
      return ctx.isOriginLocal() && ((LocalTransaction)ctx.getCacheTransaction()).isPrepareSent()
            && !((LocalTransaction)ctx.getCacheTransaction()).isCommitOrRollbackSent();
   }

   protected CompletableFuture<Object> totalOrderPrepare(TxInvocationContext<?> ctx, PrepareCommand command,
                                                         Collection<Address> recipients,
                                                         TimeoutValidationResponseFilter responseFilter) {
      try {
         Set<Address> realRecipients = null;
         if (recipients != null) {
            realRecipients = new HashSet<>(recipients);
            realRecipients.add(rpcManager.getAddress());
         }
         CompletableFuture<Map<Address, Response>> remoteInvocation =
               internalTotalOrderPrepare(realRecipients, command, responseFilter);
         return remoteInvocation.handle((responses, t) -> {
            transactionRemotelyPrepared(ctx);
            CompletableFutures.rethrowException(t);

            return null;
         });
      } catch (Throwable t) {
         transactionRemotelyPrepared(ctx);
         throw t;
      }
   }

   private CompletableFuture<Map<Address, Response>> internalTotalOrderPrepare(Collection<Address> recipients,
                                                                               PrepareCommand prepareCommand,
                                                                               TimeoutValidationResponseFilter responseFilter) {
      if (defaultSynchronous) {
         RpcOptionsBuilder builder = rpcManager.getRpcOptionsBuilder(ResponseMode.SYNCHRONOUS_IGNORE_LEAVERS, DeliverOrder.TOTAL);
         if (responseFilter != null) {
            builder.responseFilter(responseFilter);
         }
         CompletableFuture<Map<Address, Response>> remoteInvocation =
               rpcManager.invokeRemotelyAsync(recipients, prepareCommand, builder.build());
         if (responseFilter == null) {
            return remoteInvocation;
         }
         return remoteInvocation.thenApply(responses -> {
            responseFilter.validate();
            return responses;
         });
      } else {
         RpcOptionsBuilder builder = rpcManager.getRpcOptionsBuilder(ResponseMode.ASYNCHRONOUS,
                                                                     DeliverOrder.TOTAL);
         return rpcManager.invokeRemotelyAsync(recipients, prepareCommand, builder.build());
      }
   }

   protected final boolean isSyncCommitPhase() {
      return cacheConfiguration.transaction().syncCommitPhase();
   }

   protected final TimeoutValidationResponseFilter getSelfDeliverFilter() {
      return new SelfDeliverFilter(rpcManager.getAddress());
   }
}
