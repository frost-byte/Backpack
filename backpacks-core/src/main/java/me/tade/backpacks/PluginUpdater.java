package me.tade.backpacks;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import org.bukkit.scheduler.BukkitRunnable;

public class PluginUpdater {
	private Backpacks plugin;
	private boolean needUpdate;
	private String[] updateInfo;

	PluginUpdater(Backpacks plugin) {
		this.plugin = plugin;
		(new BukkitRunnable() {
			public void run() {
				PluginUpdater.this.doUpdate();
			}
		}).runTaskTimerAsynchronously(plugin, 20L, 36000L);
	}

	private void doUpdate() {
		String response = this.getResponse();
		if (response == null) {
			System.out.println("Some sort of error happened! Can't get new version of Backpack!");
		} else {
			this.updateInfo = response.split(";");
			System.out.println("Current Backpack version: " + this.plugin.getDescription().getVersion());
			System.out.println("New Backpack version: " + this.updateInfo[0]);
			if (!this.plugin.getDescription().getVersion().equalsIgnoreCase(this.updateInfo[0])) {
				System.out.println(" ");
				System.out.println("Backpack has a new Update!");
				this.needUpdate = true;
				this.plugin.sendUpdateMessage();
			}
		}
	}

	private String getResponse() {
		try {
			System.out.println("Checking for new version of Backpack...");
			URL post = new URL("https://raw.githubusercontent.com/frost-byte/Backpack/master/VERSION");
			return this.get(post);
		} catch (IOException var3) {
			var3.printStackTrace();
			return null;
		}
	}

	private String get(URL url)
	{
		BufferedReader br = null;

		try
		{
			br = new BufferedReader(new InputStreamReader(url.openStream()));
			StringBuilder sb = new StringBuilder();

			String line;
			while((line = br.readLine()) != null) {
				sb.append(line);
				sb.append(System.lineSeparator());
			}

			return sb.toString();
		}
		catch (IOException e)
		{
			e.printStackTrace();
		}
		finally
		{
			if (br != null) {
				try {
					br.close();
				} catch (IOException e) {
					e.printStackTrace();
				}
			}

		}

		return null;
	}

	public boolean needUpdate() {
		return this.needUpdate;
	}

	String[] getUpdateInfo() {
		return this.updateInfo;
	}
}
