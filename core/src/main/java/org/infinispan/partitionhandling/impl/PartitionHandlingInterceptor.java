package org.infinispan.partitionhandling.impl;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import org.infinispan.commands.DataCommand;
import org.infinispan.commands.LocalFlagAffectedCommand;
import org.infinispan.commands.read.EntrySetCommand;
import org.infinispan.commands.read.GetAllCommand;
import org.infinispan.commands.read.GetCacheEntryCommand;
import org.infinispan.commands.read.GetKeyValueCommand;
import org.infinispan.commands.read.KeySetCommand;
import org.infinispan.commands.tx.CommitCommand;
import org.infinispan.commands.tx.PrepareCommand;
import org.infinispan.commands.write.ApplyDeltaCommand;
import org.infinispan.commands.write.ClearCommand;
import org.infinispan.commands.write.DataWriteCommand;
import org.infinispan.commands.write.PutKeyValueCommand;
import org.infinispan.commands.write.PutMapCommand;
import org.infinispan.commands.write.RemoveCommand;
import org.infinispan.commands.write.ReplaceCommand;
import org.infinispan.commons.util.InfinispanCollections;
import org.infinispan.context.Flag;
import org.infinispan.context.InvocationContext;
import org.infinispan.context.impl.TxInvocationContext;
import org.infinispan.distribution.DistributionManager;
import org.infinispan.factories.annotations.Inject;
import org.infinispan.interceptors.BasicInvocationStage;
import org.infinispan.interceptors.DDAsyncInterceptor;
import org.infinispan.partitionhandling.AvailabilityMode;
import org.infinispan.remoting.RpcException;
import org.infinispan.remoting.transport.Transport;
import org.infinispan.util.logging.Log;
import org.infinispan.util.logging.LogFactory;

public class PartitionHandlingInterceptor extends DDAsyncInterceptor {
   private static final Log log = LogFactory.getLog(PartitionHandlingInterceptor.class);

   private PartitionHandlingManager partitionHandlingManager;
   private Transport transport;
   private DistributionManager distributionManager;

   @Inject
   void init(PartitionHandlingManager partitionHandlingManager, Transport transport,
         DistributionManager distributionManager) {
      this.partitionHandlingManager = partitionHandlingManager;
      this.transport = transport;
      this.distributionManager = distributionManager;
   }

   private boolean performPartitionCheck(InvocationContext ctx, LocalFlagAffectedCommand command) {
      // We always perform partition check if this is a remote command
      if (!ctx.isOriginLocal()) {
         return true;
      }
      Set<Flag> flags = command.getFlags();
      return flags == null || !flags.contains(Flag.CACHE_MODE_LOCAL);
   }

   @Override
   public BasicInvocationStage visitPutKeyValueCommand(InvocationContext ctx, PutKeyValueCommand command)
         throws Throwable {
      return handleSingleWrite(ctx, command);
   }

   @Override
   public BasicInvocationStage visitRemoveCommand(InvocationContext ctx, RemoveCommand command) throws Throwable {
      return handleSingleWrite(ctx, command);
   }

   @Override
   public BasicInvocationStage visitReplaceCommand(InvocationContext ctx, ReplaceCommand command) throws Throwable {
      return handleSingleWrite(ctx, command);
   }

   protected BasicInvocationStage handleSingleWrite(InvocationContext ctx, DataWriteCommand command) throws Throwable {
      if (performPartitionCheck(ctx, command)) {
         partitionHandlingManager.checkWrite(command.getKey());
      }
      return handleDefault(ctx, command);
   }

   @Override
   public BasicInvocationStage visitPutMapCommand(InvocationContext ctx, PutMapCommand command) throws Throwable {
      if (performPartitionCheck(ctx, command)) {
         for (Object k : command.getAffectedKeys())
            partitionHandlingManager.checkWrite(k);
      }
      return handleDefault(ctx, command);
   }

   @Override
   public BasicInvocationStage visitClearCommand(InvocationContext ctx, ClearCommand command) throws Throwable {
      if (performPartitionCheck(ctx, command)) {
         partitionHandlingManager.checkClear();
      }
      return handleDefault(ctx, command);
   }

   @Override
   public BasicInvocationStage visitApplyDeltaCommand(InvocationContext ctx, ApplyDeltaCommand command)
         throws Throwable {
      return handleSingleWrite(ctx, command);
   }

   @Override
   public BasicInvocationStage visitKeySetCommand(InvocationContext ctx, KeySetCommand command) throws Throwable {
      if (performPartitionCheck(ctx, command)) {
         partitionHandlingManager.checkBulkRead();
      }
      return handleDefault(ctx, command);
   }

   @Override
   public BasicInvocationStage visitEntrySetCommand(InvocationContext ctx, EntrySetCommand command) throws Throwable {
      if (performPartitionCheck(ctx, command)) {
         partitionHandlingManager.checkBulkRead();
      }
      return handleDefault(ctx, command);
   }

   @Override
   public final BasicInvocationStage visitGetKeyValueCommand(InvocationContext ctx, GetKeyValueCommand command)
         throws Throwable {
      return handleDataReadCommand(ctx, command);
   }

   @Override
   public final BasicInvocationStage visitGetCacheEntryCommand(InvocationContext ctx, GetCacheEntryCommand command)
         throws Throwable {
      return handleDataReadCommand(ctx, command);
   }

   private BasicInvocationStage handleDataReadCommand(InvocationContext ctx, DataCommand command) {
      return invokeNext(ctx, command).handle((rCtx, rCommand, rv, t) -> {
         DataCommand dataCommand = (DataCommand) rCommand;
         if (t != null) {
            if (t instanceof RpcException && performPartitionCheck(rCtx, dataCommand)) {
               // We must have received an AvailabilityException from one of the owners.
               // There is no way to verify the cause here, but there isn't any other way to get an invalid
               // get response.
               throw log.degradedModeKeyUnavailable(dataCommand.getKey());
            } else {
               throw t;
            }
         }

         postOperationPartitionCheck(rCtx, dataCommand, dataCommand.getKey());
      });
   }

   @Override
   public BasicInvocationStage visitPrepareCommand(TxInvocationContext ctx, PrepareCommand command) throws Throwable {
      if (!ctx.isOriginLocal()) {
         return invokeNext(ctx, command);
      }
      return invokeNext(ctx, command).thenAccept(
            (rCtx, rCommand, rv) -> postTxCommandCheck(((TxInvocationContext) rCtx)));
   }

   @Override
   public BasicInvocationStage visitCommitCommand(TxInvocationContext ctx, CommitCommand command) throws Throwable {
      if (!ctx.isOriginLocal()) {
         return invokeNext(ctx, command);
      }
      return invokeNext(ctx, command).thenAccept(
            (rCtx, rCommand, rv) -> postTxCommandCheck(((TxInvocationContext) rCtx)));
   }

   protected void postTxCommandCheck(TxInvocationContext ctx) {
      if (ctx.hasModifications() && partitionHandlingManager.getAvailabilityMode() != AvailabilityMode.AVAILABLE && !partitionHandlingManager.isTransactionPartiallyCommitted(ctx.getGlobalTransaction())) {
         for (Object key : ctx.getAffectedKeys()) {
            partitionHandlingManager.checkWrite(key);
         }
      }
   }

   private void postOperationPartitionCheck(InvocationContext ctx, DataCommand command, Object key) throws Throwable {
      if (performPartitionCheck(ctx, command)) {
         // We do the availability check after the read, because the cache may have entered degraded mode
         // while we were reading from a remote node.
         partitionHandlingManager.checkRead(key);
      }
      // TODO We can still return a stale value if the other partition stayed active without us and we haven't entered degraded mode yet.
   }

   @Override
   public BasicInvocationStage visitGetAllCommand(InvocationContext ctx, GetAllCommand command) throws Throwable {
      return invokeNext(ctx, command).handle((rCtx, rCommand, rv, t) -> {
         GetAllCommand getAllCommand = (GetAllCommand) rCommand;
         if (t != null) {
            if (t instanceof RpcException && performPartitionCheck(rCtx, getAllCommand)) {
               // We must have received an AvailabilityException from one of the owners.
               // There is no way to verify the cause here, but there isn't any other way to get an invalid
               // get response.
               throw log.degradedModeKeysUnavailable(((GetAllCommand) rCommand).getKeys());
            } else {
               throw t;
            }
         }

         if (performPartitionCheck(rCtx, getAllCommand)) {
            // We do the availability check after the read, because the cache may have entered degraded mode
            // while we were reading from a remote node.
            for (Object key : getAllCommand.getKeys()) {
               partitionHandlingManager.checkRead(key);
            }

            Map<Object, Object> result = ((Map<Object, Object>) rv);
            // If all owners left and we still haven't received the availability update yet, we could return
            // an incorrect value. So we need a special check for missing results.
            if (result.size() != getAllCommand.getKeys().size()) {
               // Unlike in PartitionHandlingManager.checkRead(), here we ignore the availability status
               // and we only fail the operation if _all_ owners have left the cluster.
               // TODO Move this to the availability strategy when implementing ISPN-4624
               Set<Object> missingKeys = new HashSet<>(getAllCommand.getKeys());
               missingKeys.removeAll(result.keySet());
               for (Iterator<Object> it = missingKeys.iterator(); it.hasNext(); ) {
                  Object key = it.next();
                  if (InfinispanCollections.containsAny(transport.getMembers(), distributionManager.locate(key))) {
                     it.remove();
                  }
               }
               if (!missingKeys.isEmpty()) {
                  throw log.degradedModeKeysUnavailable(missingKeys);
               }
            }
         }

         // TODO We can still return a stale value if the other partition stayed active without us and we haven't entered degraded mode yet.
      });
   }
}
