package com.petrolpark.destroy.block.entity;

import java.text.DecimalFormat;
import java.util.List;
import java.util.Optional;

import javax.annotation.Nullable;

import org.jetbrains.annotations.NotNull;
import org.joml.Math;

import com.petrolpark.destroy.block.DestroyBlocks;
import com.petrolpark.destroy.capability.blockEntity.VatSideTankCapability;
import com.petrolpark.destroy.config.DestroyAllConfigs;
import com.petrolpark.destroy.util.DestroyLang;
import com.petrolpark.destroy.util.DestroyLang.TemperatureUnit;
import com.petrolpark.destroy.util.vat.IUVLampBlock;
import com.petrolpark.destroy.util.vat.IVatHeaterBlock;
import com.petrolpark.destroy.util.vat.Vat;
import com.petrolpark.destroy.util.vat.VatMaterial;
import com.simibubi.create.content.decoration.copycat.CopycatBlockEntity;
import com.simibubi.create.content.equipment.goggles.IHaveGoggleInformation;
import com.simibubi.create.content.equipment.goggles.IHaveHoveringInformation;
import com.simibubi.create.content.fluids.FluidFX;
import com.simibubi.create.content.fluids.FluidTransportBehaviour;
import com.simibubi.create.content.fluids.FluidTransportBehaviour.AttachmentTypes;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.fluid.SmartFluidTankBehaviour;
import com.simibubi.create.foundation.item.TooltipHelper;
import com.simibubi.create.foundation.utility.VecHelper;
import com.simibubi.create.foundation.utility.animation.LerpedFloat;
import com.simibubi.create.foundation.utility.animation.LerpedFloat.Chaser;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.fluids.capability.IFluidHandler.FluidAction;
import net.minecraftforge.items.IItemHandler;

public class VatSideBlockEntity extends CopycatBlockEntity implements IHaveGoggleInformation, IHaveHoveringInformation {

    private static final int BUFFER_TANK_CAPACITY = 1000;

    private static final DecimalFormat df = new DecimalFormat();
    static {
        df.setMinimumFractionDigits(1);
        df.setMaximumFractionDigits(1);
    };

    protected SmartFluidTankBehaviour inputBehaviour;
    protected LazyOptional<IFluidHandler> fluidCapability;

    protected LazyOptional<IItemHandler> itemCapability;

    /**
     * The power in/output from a {@link com.petrolpark.destroy.util.vat.IVatHeaterBlock heater or cooler} this
     * Vat Side had last time its adjacent block was changed.
     */
    protected float oldPower;
    /**
     * The UV power this Vat Side had last time its adjacent block was changed.
     */
    protected float oldUV;

    public Direction direction; // The outward direction this side is facing
    public BlockPos controllerPosition;

    protected DisplayType displayType;
    public int spoutingTicks;
    public FluidStack spoutingFluid;
    public LerpedFloat ventOpenness = LerpedFloat.linear().startWithValue(0f);

    public VatSideBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
        displayType = DisplayType.NORMAL;
        spoutingTicks = 0;
        spoutingFluid = FluidStack.EMPTY;
        oldPower = 0f;
        oldUV = 0f;
        fluidCapability = LazyOptional.empty();
        itemCapability = LazyOptional.empty();
        refreshItemCapability();
        refreshFluidCapability();
    };

    @Override
    protected AABB createRenderBoundingBox() {
        return super.createRenderBoundingBox();
	};

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
        inputBehaviour = new SmartFluidTankBehaviour(SmartFluidTankBehaviour.TYPE, this, 1, BUFFER_TANK_CAPACITY, false)
            .whenFluidUpdates(this::tryInsertFluidInVat)
            .forbidExtraction();
        behaviours.add(inputBehaviour);
        fluidCapability = LazyOptional.empty(); // Temporarily set this to an empty optional
        refreshFluidCapability();
    };

    @Override
    public void destroy() {
        super.destroy();
        VatControllerBlockEntity vatController = getController();
        if (vatController != null && !vatController.underDeconstruction) vatController.deleteVat(getBlockPos());
    };

    protected void refreshFluidCapability() {
        if (fluidCapability.isPresent()) return;
        fluidCapability = LazyOptional.of(() -> {
            // Allow inserting into this side tank (which is then mixed into the main tank)
            LazyOptional<? extends IFluidHandler> inputCap = inputBehaviour.getCapability();
            // Allow withdrawing from the main tank
            LazyOptional<? extends IFluidHandler> liquidOutputCap = LazyOptional.empty();
            LazyOptional<? extends IFluidHandler> gasOutputCap = LazyOptional.empty();
            VatControllerBlockEntity vatController = getController();
            if (vatController != null) {
                liquidOutputCap = LazyOptional.of(() -> vatController.getLiquidTank());
                gasOutputCap = LazyOptional.of(() -> vatController.getGasTank());
            };
			return new VatSideTankCapability(this, liquidOutputCap.orElse(null), gasOutputCap.orElse(null), inputCap.orElse(null));
        });
    };

    protected void refreshItemCapability() {
        if (itemCapability.isPresent()) return;
        VatControllerBlockEntity vatController = getController();
        if (vatController != null) {
            itemCapability = vatController.itemCapability;
        } else {
            itemCapability = LazyOptional.empty();
        };
    };

    @Override
    public void invalidateCaps() {
        fluidCapability.invalidate();
        itemCapability.invalidate();
    };

    @Nullable
    @SuppressWarnings("null")
    public VatControllerBlockEntity getController() {
        if (!hasLevel() || controllerPosition == null) return null;
        BlockEntity be = getLevel().getBlockEntity(controllerPosition); // It thinks getLevel() might be null (it's not)
        if (!(be instanceof VatControllerBlockEntity vatController)) return null;
        return vatController;
    };

    public Optional<Vat> getVatOptional() {
        VatControllerBlockEntity vatController = getController();
        if (vatController == null) return Optional.empty();
        return vatController.getVatOptional();
    };

    public void tryInsertFluidInVat() {
        VatControllerBlockEntity vatController = getController();
        if (vatController == null || inputBehaviour.getPrimaryHandler().isEmpty()) return;
        refreshFluidCapability();
        // Attempt to transfer Fluid from this Vat Side Block Entity to the Controller's Tank, which is the main one
        inputBehaviour.allowExtraction();
        // Determine how much Fluid could be added to the main tank (this should usually be everything)
        FluidStack drainedFluid = inputBehaviour.getPrimaryHandler().drain(BUFFER_TANK_CAPACITY, FluidAction.SIMULATE);
        int drainedAmount = vatController.addFluid(drainedFluid, FluidAction.EXECUTE);
        // Drain that amount from this Vat Side Block Entity's Tank
        inputBehaviour.getPrimaryHandler().drain(drainedAmount, FluidAction.EXECUTE);
        if (drainedAmount > 0) { // Let the renderer know we should animate Fluid being dispensed
            spoutingTicks = 10;
            spoutingFluid = drainedFluid;
            sendData();
        };
        inputBehaviour.forbidExtraction();
    };

    @Override
    @SuppressWarnings("null")
    public void tick() {
        super.tick();
 
        if (getLevel().isClientSide()) { // It thinks getLevel() might be null (it's not)
            ventOpenness.tickChaser();
            if (spoutingTicks > 0) {
                spoutingTicks--;
                if (!isPipeSubmerged(true, null)) spawnParticles(spoutingFluid, getLevel());
            };
        };
        tryInsertFluidInVat();
    };

    @Override
    protected void read(CompoundTag tag, boolean clientPacket) {
        super.read(tag, clientPacket);
        if (tag.contains("Side")) {
            direction = Direction.values()[tag.getInt("Side")];
        } else {
            direction = null;
        };
        controllerPosition = NbtUtils.readBlockPos(tag.getCompound("ControllerPosition"));
        displayType = DisplayType.values()[tag.getInt("DisplayType")];
        oldPower = tag.getFloat("OldHeatingPower");
        oldUV = tag.getFloat("OldUVPower");
        if (clientPacket) {
            spoutingTicks = tag.getInt("SpoutingTicks");
            spoutingFluid = FluidStack.loadFluidStackFromNBT(tag.getCompound("SpoutingFluid"));
            ventOpenness.chase(displayType == DisplayType.OPEN_VENT ? 1f : 0f, 0.3f, Chaser.EXP);
        };
    };

    @Override
    protected void write(CompoundTag tag, boolean clientPacket) {
        super.write(tag, clientPacket);
        if (direction != null) tag.putInt("Side", direction.ordinal());
        if (controllerPosition != null) tag.put("ControllerPosition", NbtUtils.writeBlockPos(controllerPosition));
        tag.putInt("DisplayType", displayType.ordinal());
        tag.putFloat("OldHeatingPower", oldPower);
        tag.putFloat("OldUVPower", oldUV);
        if (clientPacket) {
            tag.putInt("SpoutingTicks", spoutingTicks);
            if (!spoutingFluid.isEmpty()) tag.put("SpoutingFluid", spoutingFluid.writeToNBT(new CompoundTag()));
        };
    };

    @Override
    public <T> @NotNull LazyOptional<T> getCapability(@NotNull Capability<T> cap, @Nullable Direction side) {
        if (side != direction) return LazyOptional.empty();
        if (isFluidHandlerCap(cap) && (displayType == DisplayType.NORMAL || displayType == DisplayType.PIPE)) {
            return fluidCapability.cast();
        };
        if (isItemHandlerCap(cap)) {
            refreshItemCapability();
            return itemCapability.cast();
        };
        return super.getCapability(cap, side);
    };

    /**
     * @see VatControllerBlockEntity#getPercentagePressure()
     */
    public float getPercentagePressure() {
        VatControllerBlockEntity controller = getController();
        if (controller == null) return 0f;
        return controller.getPercentagePressure();
    };

    /**
     * Distance from the bottom of the pipe spout (if this Vat Side were to be a {@link DisplayType#PIPE pipe}) to the base of the Vat.
     */
    public float pipeHeightAboveVatBase() {
        if (getVatOptional().isEmpty() || direction == Direction.DOWN) return 0f;
        if (direction == Direction.UP) return getVatOptional().get().getInternalHeight();
        return getBlockPos().getY() - getVatOptional().get().getInternalLowerCorner().getY() + 4 / 16f;
    };

    /**
     * Whether this Vat side would be submerged if it were a {@link DisplayType#PIPE pipe}.
     * @param client Whether this is client-side
     * @param partialTicks Not required server-side
     */
    public boolean isPipeSubmerged(boolean client, @Nullable Float partialTicks) {
        VatControllerBlockEntity controller = getController();
        if (controller == null) return false;
        if (direction == Direction.DOWN) return true;
        if (direction == Direction.UP) return !controller.canFitFluid();
        return pipeHeightAboveVatBase() < (client ? controller.getRenderedFluidLevel(partialTicks == null ? 0f : partialTicks) : controller.getFluidLevel());
    };

    @Override
    public void setMaterial(BlockState blockState) {
        if (blockState.is(DestroyBlocks.VAT_SIDE.get())) return;
        super.setMaterial(blockState);
    };

    @SuppressWarnings("null")
    public void setPowerFromAdjacentBlock(BlockPos heaterOrLampPos) {
        if (!hasLevel() | getLevel().isClientSide()) return;
        VatControllerBlockEntity vatController = getController();
        if (vatController == null) return;

        float newPower = IVatHeaterBlock.getHeatingPower(getLevel(), heaterOrLampPos, direction.getOpposite());
        if (newPower != oldPower) {
            vatController.changeHeatingPower(newPower - oldPower);
            oldPower = newPower;
        };

        float newUVPower = 0f;
        if (VatMaterial.getMaterial(getMaterial().getBlock()).map(VatMaterial::transparent).orElse(false)) {
            newUVPower = IUVLampBlock.getUVPower(getLevel(), heaterOrLampPos, direction.getOpposite());
            if (newUVPower == 0f) {
                if (direction == Direction.UP && getLevel().canSeeSky(getBlockPos())) newUVPower = 10f; // It thinks getLevel() might be null
            };
        };
        if (newUVPower != oldUV) {
            vatController.changeUVPower(newUVPower - oldUV);
            oldUV = newUVPower;
        };

        sendData();
    };

    public static enum DisplayType {
        NORMAL,
        BAROMETER,
        THERMOMETER,
        PIPE,
        CLOSED_VENT,
        OPEN_VENT;

        public boolean validForTop() {
            return this != BAROMETER && this != THERMOMETER;
        };

        public boolean validForBottom() {
            return validForTop() && this != CLOSED_VENT && this != OPEN_VENT;
        };

        public boolean validForSide() {
            return this != OPEN_VENT && this != CLOSED_VENT;
        };

        public boolean isVent() {
            return this == CLOSED_VENT || this == OPEN_VENT;
        };
    };

    public DisplayType getDisplayType() {
        return displayType;
    };

    @SuppressWarnings("null") // It thinks getLevel() might be null (it's not)
    public void setDisplayType(DisplayType displayType) {
        if (this.displayType == displayType) return;
        boolean updateVent = this.displayType == DisplayType.OPEN_VENT;
        this.displayType = displayType;
        if (!hasLevel()) return;

        if (getLevel().isClientSide()) ventOpenness.chase(displayType == DisplayType.OPEN_VENT ? 1f : 0f, 0.3f, Chaser.EXP);

        getBlockState().updateNeighbourShapes(getLevel(), getBlockPos(), 3);
        updateDisplayType(getBlockPos().relative(direction)); // Check for a Pipe
        sendData();

        VatControllerBlockEntity controller = getController();
        if (controller == null) return;
        if (updateVent) controller.removeVent();
        if (controller.openVentPos == null && displayType == DisplayType.OPEN_VENT) controller.openVentPos = getBlockPos();
    };

    @SuppressWarnings("null")
    public void updateDisplayType(BlockPos neighborPos) {
        if (getController() == null) return;
        FluidTransportBehaviour behaviour = BlockEntityBehaviour.get(getLevel(), neighborPos, FluidTransportBehaviour.TYPE);
        boolean nextToPipe = false;
        if (behaviour != null) {
            AttachmentTypes attachment = behaviour.getRenderedRimAttachment(getLevel(), neighborPos, behaviour.blockEntity.getBlockState(), direction.getOpposite());
            if (attachment == AttachmentTypes.DRAIN || attachment == AttachmentTypes.PARTIAL_DRAIN) nextToPipe = true;
        };
        boolean nextToAir = getLevel().getBlockState(neighborPos).isAir(); // It thinks getLevel() might be null (it's not)
        
        if (nextToPipe) {
            setDisplayType(DisplayType.PIPE);
        } else if (!nextToAir) {
            setDisplayType(DisplayType.NORMAL);
        } else {
            if (getDisplayType() == DisplayType.PIPE) setDisplayType(DisplayType.NORMAL);
            switch (direction) {
                case UP: {
                    if (!getDisplayType().validForTop()) setDisplayType(DisplayType.NORMAL);
                    break;
                } case DOWN: {
                    if (!getDisplayType().validForBottom()) setDisplayType(DisplayType.NORMAL);
                    break;
                } default: {
                    if (!getDisplayType().validForSide()) setDisplayType(DisplayType.NORMAL);
                }
            };
        };

        invalidateRenderBoundingBox();
    };

    @SuppressWarnings("null")
    public void updateRedstone() {
        if (!hasLevel()) return;
        boolean hasPower = getLevel().hasNeighborSignal(getBlockPos()); // It thinks getLevel() might be null
        if (getDisplayType() == DisplayType.OPEN_VENT && hasPower) setDisplayType(DisplayType.CLOSED_VENT);
        if (getDisplayType() == DisplayType.CLOSED_VENT && !hasPower) setDisplayType(DisplayType.OPEN_VENT);
    };

    protected void spawnParticles(FluidStack fluid, Level level) {
		if (isVirtual()) return;
		Vec3 position = VecHelper.getCenterOf(getBlockPos().relative(direction.getOpposite()))
            .subtract(0d, direction == Direction.UP ? 0d : 3 / 16d, 0d)
            .add(Vec3.atLowerCornerOf(direction.getNormal()).scale(3 / 16f));
		ParticleOptions particle = FluidFX.getFluidParticle(fluid);
        Vec3 motion = VecHelper.offsetRandomly(Vec3.ZERO, level.random, 0.05f);
        motion = new Vec3(motion.x, Math.abs(motion.y), motion.z);
        level.addAlwaysVisibleParticle(particle, position.x, position.y, position.z, motion.x, motion.y, motion.z);
	};

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        VatControllerBlockEntity controller = getController();
        if (!getVatOptional().isPresent() || controller == null) return false;
        switch (getDisplayType()) {
            case THERMOMETER: {
                TemperatureUnit unit = DestroyAllConfigs.CLIENT.chemistry.temperatureUnit.get();
                DestroyLang.translate("tooltip.vat.temperature", unit.of(controller.getTemperature(), df)).style(ChatFormatting.WHITE).forGoggles(tooltip);
                if (DestroyAllConfigs.CLIENT.chemistry.nerdMode.get()) DestroyLang.translate("tooltip.vat.power", df.format(controller.heatingPower / 1000f)).forGoggles(tooltip);
                break;
            } case BAROMETER: {
                Vat vat = getVatOptional().get();
                DestroyLang.translate("tooltip.vat.pressure.header").style(ChatFormatting.WHITE).forGoggles(tooltip);
                DestroyLang.translate("tooltip.vat.pressure.current", df.format(controller.getPressure() / 1000f)).style(ChatFormatting.GRAY).forGoggles(tooltip, 1);
                DestroyLang.translate("tooltip.vat.pressure.max", df.format(vat.getMaxPressure() / 1000f), vat.getWeakestBlock().getBlock().getName().getString()).style(ChatFormatting.GRAY).forGoggles(tooltip, 1);
                break;
            } default: {
                VatControllerBlockEntity.vatFluidTooltip(getController(), tooltip);
            };
        };
        return true;
    };

    @Override
    public boolean addToTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        if (getDisplayType() == DisplayType.PIPE) {
            if (!isPipeSubmerged(false, null)) {
                DestroyLang.translate("tooltip.vat.not_submerged.header").style(ChatFormatting.GOLD).forGoggles(tooltip);
                TooltipHelper.cutTextComponent(DestroyLang.translate("tooltip.vat.not_submerged").component(), TooltipHelper.Palette.GRAY_AND_WHITE).forEach(component -> DestroyLang.builder().add(component.copy()).forGoggles(tooltip));
                tooltip.add(Component.literal(""));
            };
        };
        return false;
    };
};
