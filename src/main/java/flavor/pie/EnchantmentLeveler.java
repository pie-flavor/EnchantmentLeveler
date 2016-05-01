package flavor.pie;

import java.io.*;
import java.nio.file.Path;
import java.util.*;

import org.slf4j.Logger;
import org.spongepowered.api.Game;
import org.spongepowered.api.block.BlockSnapshot;
import org.spongepowered.api.command.CommandResult;
import org.spongepowered.api.command.CommandSource;
import org.spongepowered.api.command.args.CommandContext;
import org.spongepowered.api.command.spec.CommandSpec;
import org.spongepowered.api.config.ConfigDir;
import org.spongepowered.api.config.DefaultConfig;
import org.spongepowered.api.data.key.Keys;
import org.spongepowered.api.data.meta.ItemEnchantment;
import org.spongepowered.api.entity.EntityType;
import org.spongepowered.api.entity.EntityTypes;
import org.spongepowered.api.entity.living.player.Player;
import org.spongepowered.api.event.Listener;
import org.spongepowered.api.event.block.ChangeBlockEvent;
import org.spongepowered.api.event.cause.entity.damage.source.DamageSource;
import org.spongepowered.api.event.cause.entity.damage.source.EntityDamageSource;
import org.spongepowered.api.event.entity.DamageEntityEvent;
import org.spongepowered.api.event.filter.cause.First;
import org.spongepowered.api.event.game.state.GameStartingServerEvent;
import org.spongepowered.api.event.game.state.GameStoppingEvent;
import org.spongepowered.api.event.world.SaveWorldEvent;
import org.spongepowered.api.item.*;
import org.spongepowered.api.item.inventory.ItemStack;
import org.spongepowered.api.plugin.Plugin;
import org.spongepowered.api.plugin.PluginContainer;
import org.spongepowered.api.text.Text;
import org.spongepowered.api.text.format.TextColors;
import org.spongepowered.api.text.serializer.TextSerializers;

import com.google.inject.Inject;

import ninja.leaping.configurate.commented.CommentedConfigurationNode;
import ninja.leaping.configurate.loader.ConfigurationLoader;

@Plugin(id="enchantmentleveler",name="EnchantmentLeveler",version="0.0.1")
public class EnchantmentLeveler {
	boolean disabled = false;
	@Inject @DefaultConfig(sharedRoot = false) Path path;
	@Inject @ConfigDir(sharedRoot = false) File f;
	@Inject @DefaultConfig(sharedRoot = false) ConfigurationLoader<CommentedConfigurationNode> loader;
	@Inject Game game;
	@Inject PluginContainer plugin;
	@Inject Logger logger;
	CommentedConfigurationNode root;
	HashMap<UUID, HashMap<ItemType, HashMap<Enchantment, Double>>> levels;
	@Listener
	public void onEnable(GameStartingServerEvent e) {
		try {
			root = loader.load();
			if (root.getNode("version").getInt() < 1) {
				CommentedConfigurationNode itemtype = root.getNode(ItemTypes.DIAMOND_PICKAXE.getId());
				itemtype.setComment("The item used to break the block");
				CommentedConfigurationNode action = itemtype.getNode("break");
				action.setComment("What to do when a block gets broken");
				CommentedConfigurationNode rootenchant = action.getNode("enchant");
				rootenchant.setValue(Enchantments.EFFICIENCY.getId());
				rootenchant.setComment("The enchantment to use.");
				CommentedConfigurationNode rootmax = action.getNode("maxLevel");
				rootmax.setValue(5);
				rootmax.setComment("Maximum level of enchantment possible.");
				CommentedConfigurationNode rootrate = action.getNode("rate");
				rootrate.setValue(1D);
				rootrate.setComment("The xp that is gained for this specific enchantment.");
				CommentedConfigurationNode permission = action.getNode("permission");
				permission.setValue("enchantmentleveler.level");
				permission.setComment("The required permission level.");
				CommentedConfigurationNode block = action.getNode(ItemTypes.GOLD_ORE.getId());
				block.setComment("Block-specific settings. Unspecified settings are inherited from the parent.");
				block.getNode("enchant").setValue(Enchantments.FORTUNE.getId());
				block.getNode("levelFactor").setValue(1);
				CommentedConfigurationNode sword = root.getNode(ItemTypes.DIAMOND_SWORD.getId());
				CommentedConfigurationNode damage = sword.getNode("damage");
				damage.setComment("What to do when the sword is used to damage something");
				damage.getNode("enchant").setValue(Enchantments.SHARPNESS.getId());
				damage.getNode("rate").setValue(1d);
				damage.getNode("maxLevel").setValue(2d);
				root.getNode("version").setValue(1);
				loader.save(root);
			}
		} catch (IOException ex) {
			ex.printStackTrace();
			logger.error("Config unloadable! Disabling...");
			disabled = true;
			return;
		}
		levels = new HashMap<>();
		File file = new File(f, "levels.bin");
		if (file.exists()) {
			try (ObjectInputStream stream = new ObjectInputStream(new FileInputStream(file))) {
				stream.readInt();
				HashMap<UUID, HashMap<String, HashMap<String, Double>>> pre = (HashMap<UUID, HashMap<String, HashMap<String, Double>>>) stream.readObject();
				pre.forEach((id, map) -> {
					HashMap<ItemType, HashMap<Enchantment, Double>> map2 = new HashMap<>();
					map.forEach((name, map0) -> {
						HashMap<Enchantment, Double> map3 = new HashMap<>();
						map0.forEach((ench, level) -> {
							map3.put(game.getRegistry().getType(Enchantment.class, ench).get(), level);
						});
						map2.put(game.getRegistry().getType(ItemType.class, name).get(), map3);
					}); levels.put(id, map2);
				});
			} catch (IOException | ClassNotFoundException ex) {
				ex.printStackTrace();
				logger.error("Levels unloadable! Disabling...");
				disabled = true;
				return;
			}
		}
		CommandSpec reload = CommandSpec.builder().description(Text.of("Reloads the config.")).permission("enchleveler.reload").executor(this::reload).build();
		CommandSpec spec = CommandSpec.builder().executor(this::base).child(reload, "reload").build();
		game.getCommandManager().register(this, spec, "enchantmentleveler","el");
	}
	@Listener
	public void save(SaveWorldEvent e) {
		if (disabled) return;
		try (ObjectOutputStream stream = new ObjectOutputStream(new FileOutputStream(new File(f, "levels.bin")))) {
			HashMap<UUID, HashMap<String, HashMap<String, Double>>> out = new HashMap<>();
			levels.forEach((id, map) -> {
				HashMap<String, HashMap<String, Double>> map2 = new HashMap<>(); 
				map.forEach((type, map0) -> {
					HashMap<String, Double> map3 = new HashMap<>();
					map0.forEach((ench, level) -> {
						map3.put(ench.getId(), level);
					});
					map2.put(type.getId(), map3);
				}); 
				out.put(id, map2);
			});
			stream.writeInt(1);
			stream.writeObject(out);
			disabled = false;
		} catch (IOException e1) {
			e1.printStackTrace();
			logger.error("Could not save levels to enchantmentleveler/data.bin!");
		}
	}
	@Listener
	public void onDisable(GameStoppingEvent e) {
		if (disabled) return;
		try (ObjectOutputStream stream = new ObjectOutputStream(new FileOutputStream(new File(f, "levels.bin")))) {
			HashMap<UUID, HashMap<String, HashMap<String, Double>>> out = new HashMap<>();
			levels.forEach((id, map) -> {
				HashMap<String, HashMap<String, Double>> map2 = new HashMap<>();
				map.forEach((type, map0) -> {
					HashMap<String, Double> map3 = new HashMap<>();
					map0.forEach((ench, level) -> {
						map3.put(ench.getId(), level);
					});
					map2.put(type.getId(), map3);
				}); 
				out.put(id, map2);
			});
			stream.writeInt(1);
			stream.writeObject(out);
		} catch (IOException e1) {
			e1.printStackTrace();
			logger.error("Could not save levels to enchantmentleveler/data.bin!");
		}
	}
	Text c(String s) {
		return TextSerializers.FORMATTING_CODE.deserialize(s);
	}
	Text j(String s) {
		return TextSerializers.JSON.deserialize(s);
	}
	public CommandResult base(CommandSource src, CommandContext args){ 
		src.sendMessage(j("{\"text\":\"Plugin by: \",\"color\":\"green\",\"extra\":[{\"text\":\"pie_flavor\",\"color\":\"dark_green\",\"clickEvent\":{\"action\":\"open_url\",\"value\":\"https://forums.spongepowered.org/users/pie_flavor\"},\"hoverEvent\":{\"action\":\"show_text\",\"value\":\"Click to go to my Sponge page!\"}}]}"));
		return CommandResult.success();
	}
	public CommandResult reload(CommandSource src, CommandContext args) {
		try {
			root = loader.load();
			src.sendMessage(c("&aReloaded EnchantmentLeveler's configuration."));
			return CommandResult.success();
		} catch (IOException e) {
			logger.error("Config unloadable! Reload failed.");
			if (!src.equals(game.getServer().getConsole())) {
				src.sendMessage(c("&cConfig unloadable! Reload failed."));
			}
			return CommandResult.empty();
		}
	}
	@Listener
	public void onBlockBreak(ChangeBlockEvent.Break e, @First Player p){
		if (disabled) return;
		ItemStack stack = p.getItemInHand().orElse(null);
		if (stack == null) return;
		CommentedConfigurationNode nodeparent = root.getNode(stack.getItem().getId());
		if (nodeparent.isVirtual()) return;
		HashMap<ItemType, HashMap<Enchantment, Double>> map = levels.get(p.getUniqueId());
		if (map == null) map = new HashMap<>();
		CommentedConfigurationNode node = nodeparent.getNode("break");
		if (node.isVirtual()) return;
		BlockSnapshot block = e.getTransactions().get(0).getOriginal();
		CommentedConfigurationNode br = node.getNode(block.getState().getType().getId());
		boolean specific = true;
		if (br.isVirtual()) {
			br = node;
			specific = false;
		}
		Enchantment ench = game.getRegistry().getType(Enchantment.class, br.getNode("enchant").getString()).orElse(null);
		if (ench == null) {
			if (specific) {
				ench = game.getRegistry().getType(Enchantment.class, node.getNode("enchant").getString()).orElse(null);
				if (ench == null) return;
			} else return;
		}
		HashMap<Enchantment, Double> map2 = map.get(stack.getItem());
		if (map2 == null) map2 = new HashMap<>();
		double level = Optional.ofNullable(map2.get(ench)).orElse(0d);
		int max = br.getNode("maxLevel").getInt();
		if (max == 0) {
			if (specific) {
				max = node.getNode("maxLevel").getInt();
				if (max == 0) max = ench.getMaximumLevel();
			} else max = ench.getMaximumLevel();
		}
		double rate = br.getNode("rate").getDouble();
		if (rate == 0) {
			if (specific) {
				rate = node.getNode("rate").getDouble();
				if (rate == 0) rate = 0.1;
			}else rate = 0.1;
		}
		double factor = br.getNode("levelFactor").getDouble();
		if (factor == 0) {
			if (specific) {
				factor = node.getNode("factor").getDouble();
				if (factor == 0) factor = 1d;
			} else factor = 1d;
		}
		boolean requirePermission = true;
		String permission = br.getNode("permission").getString();
		if (permission == null) {
			if (specific) {
				permission = node.getNode("permission").getString();
				if (permission == null) {
					requirePermission = false;
				}
			} else requirePermission = false;
		}
		double curve = br.getNode("levelCurveFactor").getDouble();
		if (curve == 0) {
			if (specific) {
				curve = node.getNode("levelCurveFactor").getDouble();
				if (curve == 0) {
					curve = 1;
				}
			} else curve = 1;
		}
		double requirement = br.getNode("levelRequirement").getDouble();
		if (requirement == 0) {
			if (specific) {
				requirement = node.getNode("levelRequirement").getDouble();
				if (requirement == 0) requirement = 10;
			} else requirement = 10;
		}
		if (requirePermission && !p.hasPermission(permission)) return;
		List<ItemEnchantment> enchantments = stack.get(Keys.ITEM_ENCHANTMENTS).orElse(null);
		if (enchantments == null) enchantments = new ArrayList<>();
		int enchlevel = 0;
		ItemEnchantment enchantment = null;
		for (ItemEnchantment enchant : enchantments) {
			if (enchant.getEnchantment().equals(ench)) {
				enchlevel = enchant.getLevel();
				enchantment = enchant;
				break;
			}
		}
		level += rate;
		double req = requirement + (Math.pow(enchlevel, curve) * factor);
		if (level > req && enchlevel < max) {
			if (enchantment != null)
				enchantments.remove(enchantment);
			level -= req;
			ItemEnchantment enchant = new ItemEnchantment(ench, enchlevel+1);
			enchantments.add(enchant);
			stack.offer(Keys.ITEM_ENCHANTMENTS, enchantments);
			p.setItemInHand(stack);
			p.sendMessage(Text.of(TextColors.GREEN, "You leveled up your item!"));
		}
		map2.put(ench, level);
		map.put(stack.getItem(), map2);
		levels.put(p.getUniqueId(), map);
	}
	@Listener 
	public void onDamage(DamageEntityEvent e, @First DamageSource src) {
		if (!(src instanceof EntityDamageSource)) return;
		EntityDamageSource src0 = (EntityDamageSource) src;
		if (!src0.getSource().getType().equals(EntityTypes.PLAYER)) return;
		Player p = (Player) src0.getSource();
		if (disabled) return;
		ItemStack stack = p.getItemInHand().orElse(null);
		if (stack == null) return;
		CommentedConfigurationNode nodeparent = root.getNode(stack.getItem().getId());
		if (nodeparent.isVirtual()) return;
		HashMap<ItemType, HashMap<Enchantment, Double>> map = levels.get(p.getUniqueId());
		if (map == null) map = new HashMap<>();
		CommentedConfigurationNode node = nodeparent.getNode("damage");
		if (node.isVirtual()) return;
		EntityType type = e.getTargetEntity().getType();
		CommentedConfigurationNode br = node.getNode(type.getId());
		boolean specific = true;
		if (br.isVirtual()) {
			br = node;
			specific = false;
		}
		Enchantment ench = game.getRegistry().getType(Enchantment.class, br.getNode("enchant").getString()).orElse(null);
		if (ench == null) {
			if (specific) {
				ench = game.getRegistry().getType(Enchantment.class, node.getNode("enchant").getString()).orElse(null);
				if (ench == null) return;
			} else return;
		}
		HashMap<Enchantment, Double> map2 = map.get(stack.getItem());
		if (map2 == null) map2 = new HashMap<>();
		double level = Optional.ofNullable(map2.get(ench)).orElse(0d);
		int max = br.getNode("maxLevel").getInt();
		if (max == 0) {
			if (specific) {
				max = node.getNode("maxLevel").getInt();
				if (max == 0) max = ench.getMaximumLevel();
			} else max = ench.getMaximumLevel();
		}
		double rate = br.getNode("rate").getDouble();
		if (rate == 0d) {
			if (specific) {
				rate = node.getNode("rate").getDouble();
				if (rate == 0d) rate = 0.1;
			}else rate = 0.1;
		}
		double factor = br.getNode("levelFactor").getDouble();
		if (factor == 0) {
			if (specific) {
				factor = node.getNode("factor").getDouble();
				if (factor == 0) factor = 1d;
			} else factor = 1d;
		}
		boolean requirePermission = true;
		String permission = br.getNode("permission").getString();
		if (permission == null) {
			if (specific) {
				permission = node.getNode("permission").getString();
				if (permission == null) {
					requirePermission = false;
				}
			} else requirePermission = false;
		}
		double curve = br.getNode("levelCurveFactor").getDouble();
		if (curve == 0) {
			if (specific) {
				curve = node.getNode("levelCurveFactor").getDouble();
				if (curve == 0) {
					curve = 1;
				}
			} else curve = 1;
		}
		double requirement = br.getNode("levelRequirement").getDouble();
		if (requirement == 0) {
			if (specific) {
				requirement = node.getNode("levelRequirement").getDouble();
				if (requirement == 0) requirement = 10;
			} else requirement = 10;
		}
		if (requirePermission && !p.hasPermission(permission)) return;
		List<ItemEnchantment> enchantments = stack.get(Keys.ITEM_ENCHANTMENTS).orElse(null);
		if (enchantments == null) enchantments = new ArrayList<>();
		int enchlevel = 0;
		ItemEnchantment enchantment = null;
		for (ItemEnchantment enchant : enchantments) {
			if (enchant.getEnchantment().equals(ench)) {
				enchlevel = enchant.getLevel();
				enchantment = enchant;
				break;
			}
		}
		if (enchantment != null)
			enchantments.remove(enchantment);
		level += rate;
		double req = requirement + (Math.pow(enchlevel, curve) * factor);
		if (level > req && enchlevel < max) {
			level -= req;
			ItemEnchantment enchant = new ItemEnchantment(ench, enchlevel+1);
			enchantments.add(enchant);
			stack.offer(Keys.ITEM_ENCHANTMENTS, enchantments);
			p.setItemInHand(stack);
			p.sendMessage(Text.of(TextColors.GREEN, "You leveled up your item!"));
		}
		map2.put(ench, level);
		map.put(stack.getItem(), map2);
		levels.put(p.getUniqueId(), map);
	}
}
