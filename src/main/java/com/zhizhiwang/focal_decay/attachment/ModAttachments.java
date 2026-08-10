package com.zhizhiwang.focal_decay.attachment;

import com.zhizhiwang.focal_decay.FocalDecay;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.attachment.IAttachmentHolder;
import net.neoforged.neoforge.attachment.IAttachmentSerializer;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.function.Supplier;

public final class ModAttachments {
    public static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES =
            DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, FocalDecay.MODID);

    /** 玩家挖掘锁定数据。 */
    public static final Supplier<AttachmentType<BreakData>> BREAK_DATA =
            ATTACHMENT_TYPES.register("break_data", () -> AttachmentType.builder(BreakData::new)
                    .serialize(new IAttachmentSerializer<CompoundTag, BreakData>() {
                        @Override
                        public BreakData read(IAttachmentHolder holder, CompoundTag tag, HolderLookup.Provider provider) {
                            BreakData data = new BreakData();
                            data.loadNBT(tag);
                            return data;
                        }

                        @Override
                        public CompoundTag write(BreakData attachment, HolderLookup.Provider provider) {
                            CompoundTag tag = new CompoundTag();
                            attachment.saveNBT(tag);
                            return tag;
                        }
                    })
                    .copyOnDeath()
                    .build());

    private ModAttachments() {
    }
}
