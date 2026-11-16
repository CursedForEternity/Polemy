package cursed.polemy.core.events;

import cursed.polemy.Polemy;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.monster.Ravager;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = Polemy.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class GolemTankHandler {

    // 25% damage reduction per 1.0 point of incoming knockback, capped at 75% max reduction
    private static final float REDUCTION_PER_KNOCKBACK = 0.25F;
    private static final float MAX_DAMAGE_REDUCTION = 0.75F;

    @SubscribeEvent
    public static void onIronGolemHurt(LivingHurtEvent event) {
        if (!(event.getEntity() instanceof IronGolem golem) || golem.level().isClientSide()) {
            return;
        }

        // Ignore environmental damage (fall, drowning, void, /kill)
        if (event.getSource().is(DamageTypeTags.BYPASSES_ARMOR) || event.getSource().is(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
            return;
        }

        float expectedKnockback = calculateIncomingKnockback(event);
        if (expectedKnockback <= 0.0F) {
            return;
        }

        // Scale reduction: e.g. Ravager (1.5 KB + 0.5 heavy bonus = 2.0 KB) -> 50% damage reduction
        float reduction = Mth.clamp(expectedKnockback * REDUCTION_PER_KNOCKBACK, 0.0F, MAX_DAMAGE_REDUCTION);
        float reducedDamage = event.getAmount() * (1.0F - reduction);
        event.setAmount(reducedDamage);

        // Heavy metallic brace feedback on big knockback hits (like Ravagers)
        if (expectedKnockback >= 1.0F && golem.level() instanceof ServerLevel serverLevel) {
            serverLevel.playSound(null, golem.blockPosition(), SoundEvents.SHIELD_BLOCK, SoundSource.NEUTRAL, 1.2F, 0.65F);
            serverLevel.sendParticles(
                    ParticleTypes.CRIT,
                    golem.getX(),
                    golem.getY() + 1.4D,
                    golem.getZ(),
                    8, 0.4D, 0.4D, 0.4D, 0.1D
            );
        }
    }

    private static float calculateIncomingKnockback(LivingHurtEvent event) {
        float knockback = 0.0F;
        Entity attacker = event.getSource().getEntity();
        Entity directEntity = event.getSource().getDirectEntity();

        // 1. Melee attacker attributes & Knockback enchantment
        if (attacker instanceof LivingEntity livingAttacker) {
            if (livingAttacker.getAttributes().hasAttribute(Attributes.ATTACK_KNOCKBACK)) {
                knockback += (float) livingAttacker.getAttributeValue(Attributes.ATTACK_KNOCKBACK);
            }
            // Adds +0.5 knockback per level of Knockback enchantment on held weapon
            int enchantLevel = EnchantmentHelper.getKnockbackBonus(livingAttacker);
            knockback += enchantLevel * 0.5F;

            // Ravager roar & ramming bonus (Ravagers deal massive knockback in vanilla)
            if (livingAttacker instanceof Ravager) {
                knockback += 0.5F;
            }
        }

        // 2. Projectile knockback (Punch bows / heavy projectiles)
        if (directEntity instanceof AbstractArrow arrow) {
            knockback += arrow.getKnockback() * 0.5F;
        }

        // 3. Explosion knockback scaling (e.g., Creeper / Ravager roar / Fireballs)
        if (event.getSource().is(DamageTypeTags.IS_EXPLOSION)) {
            knockback += 1.5F;
        }

        return knockback;
    }
}
