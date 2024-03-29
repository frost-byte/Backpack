package me.tade.backpacks.util;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.plugin.java.JavaPlugin;

public interface VersionBridge
{
	void registerPackRecipe(JavaPlugin plugin, String packName, Player player);
	void unregisterPackRecipe(JavaPlugin plugin, String packName, Player player);
	void updatePackRecipe(JavaPlugin plugin, String packName, ShapedRecipe recipe);
	ShapedRecipe createShapedRecipe(JavaPlugin plugin, String packName, ItemStack itemStack);
	ItemStack createItemStack(Material material, int amount, short data);
}
