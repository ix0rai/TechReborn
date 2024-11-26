/*
 * This file is part of RebornCore, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2021 TeamReborn
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package reborncore.common.screen;

import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.CraftingInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ScreenHandlerListener;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.slot.Slot;
import net.minecraft.util.math.BlockPos;
import org.apache.commons.lang3.Range;
import reborncore.common.blockentity.MachineBaseBlockEntity;
import reborncore.common.network.NetworkManager;
import reborncore.common.network.clientbound.ScreenHandlerUpdatePayload;
import reborncore.common.screen.builder.SyncedObject;
import reborncore.common.util.ItemUtils;
import reborncore.common.util.RangeUtil;
import reborncore.mixin.ifaces.ServerPlayerEntityScreenHandler;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;

public class BuiltScreenHandler extends ScreenHandler {
	private final String name;

	private final Predicate<PlayerEntity> canInteract;
	private final List<Range<Integer>> playerSlotRanges;
	private final List<Range<Integer>> blockEntitySlotRanges;

	// Holds the SyncPair along with the last value
	private final Map<IdentifiedSyncedObject<?>, Object> syncPairCache = new HashMap<>();
	private final Int2ObjectMap<IdentifiedSyncedObject<?>> syncPairIdLookup = new Int2ObjectOpenHashMap<>();

	private List<Consumer<CraftingInventory>> craftEvents;

	private final MachineBaseBlockEntity blockEntity;

	public BuiltScreenHandler(int syncID, final String name, final Predicate<PlayerEntity> canInteract,
							final List<Range<Integer>> playerSlotRange,
							final List<Range<Integer>> blockEntitySlotRange, MachineBaseBlockEntity blockEntity) {
		super(null, syncID);
		this.name = name;

		this.canInteract = canInteract;

		this.playerSlotRanges = RangeUtil.joinAdjacent(playerSlotRange);
		this.blockEntitySlotRanges = RangeUtil.joinAdjacent(blockEntitySlotRange);

		this.blockEntity = blockEntity;
	}

	public void addObjectSync(final List<SyncedObject<?>> syncedObjects) {
		for (final SyncedObject<?> syncedObject : syncedObjects) {
			// Add a new sync pair to the cache with a null value
			int id = syncPairCache.size() + 1;
			var syncPair = new IdentifiedSyncedObject(syncedObject, id);
			this.syncPairCache.put(syncPair, null);
			this.syncPairIdLookup.put(id, syncPair);
		}
	}

	public void addCraftEvents(final List<Consumer<CraftingInventory>> craftEvents) {
		this.craftEvents = craftEvents;
	}

	@Override
	public boolean canUse(final PlayerEntity playerIn) {
		return this.canInteract.test(playerIn);
	}

	@Override
	public final void onContentChanged(final Inventory inv) {
		if (!this.craftEvents.isEmpty()) {
			this.craftEvents.forEach(consumer -> consumer.accept((CraftingInventory) inv));
		}
	}

	@Override
	public void sendContentUpdates() {
		super.sendContentUpdates();

		for (final ScreenHandlerListener listener : listeners) {
			sendContentUpdatePacketToListener(listener);
		}
	}

	@Override
	public void addListener(final ScreenHandlerListener listener) {
		super.addListener(listener);

		sendContentUpdatePacketToListener(listener);
	}

	private void sendContentUpdatePacketToListener(final ScreenHandlerListener listener) {
		Map<IdentifiedSyncedObject<?>, Object> updatedValues = new HashMap<>();

		this.syncPairCache.replaceAll((identifiedSyncedObject, cached) -> {
			final Object value = identifiedSyncedObject.get();

			if (!value.equals(cached)) {
				updatedValues.put(identifiedSyncedObject, value);
				return value;
			}
			return null;
		});

		if (updatedValues.isEmpty()) {
			return;
		}

		byte[] data = writeScreenHandlerData(updatedValues);
		if (listener instanceof ServerPlayerEntityScreenHandler serverPlayerEntityScreenHandler) {
			NetworkManager.sendToPlayer(new ScreenHandlerUpdatePayload(data), serverPlayerEntityScreenHandler.rc_getServerPlayerEntity());
		}
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	private byte[] writeScreenHandlerData(Map<IdentifiedSyncedObject<?>, Object> updatedValues) {
		RegistryByteBuf byteBuf = new RegistryByteBuf(PacketByteBufs.create(), blockEntity.getWorld().getRegistryManager());

		byteBuf.writeInt(updatedValues.size());
		for (Map.Entry<IdentifiedSyncedObject<?>, Object> entry : updatedValues.entrySet()) {
			PacketCodec codec = entry.getKey().object().codec();
			byteBuf.writeInt(entry.getKey().id());
			codec.encode(byteBuf, entry.getValue());
		}

		return byteBuf.array();
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	public void applyScreenHandlerData(byte[] data) {
		RegistryByteBuf byteBuf = new RegistryByteBuf(new PacketByteBuf(Unpooled.wrappedBuffer(data)), blockEntity.getWorld().getRegistryManager());
		int size = byteBuf.readInt();

		for (int i = 0; i < size; i++) {
			int id = byteBuf.readInt();
			IdentifiedSyncedObject syncedObject = syncPairIdLookup.get(id);
			Object value = syncedObject.object().codec().decode(byteBuf);
			syncedObject.set(value);
		}
	}

	@Override
	public ItemStack quickMove(final PlayerEntity player, final int index) {

		ItemStack originalStack = ItemStack.EMPTY;

		final Slot slot = this.slots.get(index);

		if (slot != null && slot.hasStack()) {

			final ItemStack stackInSlot = slot.getStack();
			originalStack = stackInSlot.copy();

			boolean shifted = false;

			for (final Range<Integer> range : this.playerSlotRanges) {
				if (range.contains(index)) {

					if (this.shiftToBlockEntity(stackInSlot)) {
						shifted = true;
					}
					break;
				}
			}

			if (!shifted) {
				for (final Range<Integer> range : this.blockEntitySlotRanges) {
					if (range.contains(index)) {
						if (this.shiftToPlayer(stackInSlot)) {
							shifted = true;
						}
						break;
					}
				}
			}

			slot.onQuickTransfer(stackInSlot, originalStack);
			if (stackInSlot.getCount() <= 0) {
				slot.setStack(ItemStack.EMPTY);
			} else {
				slot.markDirty();
			}
			if (stackInSlot.getCount() == originalStack.getCount()) {
				return ItemStack.EMPTY;
			}
			slot.onTakeItem(player, stackInSlot);
		}
		return originalStack;

	}

	protected boolean shiftItemStack(final ItemStack stackToShift, final int start, final int end) {
		if (stackToShift.isEmpty()) {
			return false;
		}
		int inCount = stackToShift.getCount();

		// First lets see if we have the same item in a slot to merge with
		for (int slotIndex = start; stackToShift.getCount() > 0 && slotIndex < end; slotIndex++) {
			final Slot slot = this.slots.get(slotIndex);
			final ItemStack stackInSlot = slot.getStack();
			int maxCount = Math.min(stackToShift.getMaxCount(), slot.getMaxItemCount());

			if (!stackToShift.isEmpty() && slot.canInsert(stackToShift)) {
				if (ItemUtils.isItemEqual(stackInSlot, stackToShift, true, false)) {
					// Got 2 stacks that need merging
					int freeStackSpace = maxCount - stackInSlot.getCount();
					if (freeStackSpace > 0) {
						int transferAmount = Math.min(freeStackSpace, stackToShift.getCount());
						stackInSlot.increment(transferAmount);
						stackToShift.decrement(transferAmount);
					}
				}
			}
		}

		// If not lets go find the next free slot to insert our remaining stack
		for (int slotIndex = start; stackToShift.getCount() > 0 && slotIndex < end; slotIndex++) {
			final Slot slot = this.slots.get(slotIndex);
			final ItemStack stackInSlot = slot.getStack();

			if (stackInSlot.isEmpty() && slot.canInsert(stackToShift)) {
				int maxCount = Math.min(stackToShift.getMaxCount(), slot.getMaxItemCount());

				int moveCount = Math.min(maxCount, stackToShift.getCount());
				ItemStack moveStack = stackToShift.copy();
				moveStack.setCount(moveCount);
				slot.setStack(moveStack);
				stackToShift.decrement(moveCount);
			}
		}

		// If we moved some, but still have more left over lets try again
		if (!stackToShift.isEmpty() && stackToShift.getCount() != inCount) {
			shiftItemStack(stackToShift, start, end);
		}

		return stackToShift.getCount() != inCount;
	}

	private boolean shiftToBlockEntity(final ItemStack stackToShift) {
		if (!blockEntity.getOptionalInventory().isPresent()) {
			return false;
		}
		for (final Range<Integer> range : this.blockEntitySlotRanges) {
			if (this.shiftItemStack(stackToShift, range.getMinimum(), range.getMaximum() + 1)) {
				return true;
			}
		}
		return false;
	}

	private boolean shiftToPlayer(final ItemStack stackToShift) {
		for (final Range<Integer> range : this.playerSlotRanges) {
			if (this.shiftItemStack(stackToShift, range.getMinimum(), range.getMaximum() + 1)) {
				return true;
			}
		}
		return false;
	}

	public String getName() {
		return this.name;
	}

	@Override
	public Slot addSlot(Slot slotIn) {
		return super.addSlot(slotIn);
	}

	public MachineBaseBlockEntity getBlockEntity() {
		return blockEntity;
	}

	public BlockPos getPos() {
		return getBlockEntity().getPos();
	}

	ScreenHandlerType<BuiltScreenHandler> type = null;

	public void setType(ScreenHandlerType<BuiltScreenHandler> type) {
		this.type = type;
	}

	@Override
	public ScreenHandlerType<BuiltScreenHandler> getType() {
		return type;
	}

	private record IdentifiedSyncedObject<T>(SyncedObject<T> object, int id) {
		public T get() {
			return object.getter().get();
		}

		public void set(T value) {
			object.setter().accept(value);
		}
	}
}
