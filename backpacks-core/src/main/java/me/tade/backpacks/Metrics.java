package me.tade.backpacks;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;

import java.util.logging.Level;
import java.util.zip.GZIPOutputStream;
import javax.net.ssl.HttpsURLConnection;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.json.JSONArray;
import org.json.JSONObject;

public class Metrics {
	private static final int B_STATS_VERSION = 1;
	private static final String URL = "https://bStats.org/submitData/bukkit";
	private static boolean logFailedRequests;
	private static String serverUUID;
	private final JavaPlugin plugin;
	private final List<Metrics.CustomChart> charts = new ArrayList<>();

	Metrics(JavaPlugin plugin)
	{
		if (plugin == null) {
			throw new IllegalArgumentException("Plugin cannot be null!");
		}
		else {
			this.plugin = plugin;
			File bStatsFolder = new File(plugin.getDataFolder().getParentFile(), "bStats");
			File configFile = new File(bStatsFolder, "config.yml");
			YamlConfiguration config = YamlConfiguration.loadConfiguration(configFile);

			if (!config.isSet("serverUuid"))
			{
				config.addDefault("enabled", true);
				config.addDefault("serverUuid", UUID.randomUUID().toString());
				config.addDefault("logFailedRequests", false);
				config.options().header(
					"bStats collects some data for plugin authors like how many servers are using their plugins.\n" +
					"To honor their work, you should not disable it.\n" +
					"This has nearly no effect on the server performance!\n" +
					"Check out https://bStats.org/ to learn more :)"
				).copyDefaults(true);

				try
				{
					config.save(configFile);
				}
				catch (IOException ignored) {}
			}

			serverUUID = config.getString("serverUuid");
			logFailedRequests = config.getBoolean("logFailedRequests", false);

			if (config.getBoolean("enabled", true))
			{
				boolean found = false;

				for (Class<?> aClass : Bukkit.getServicesManager().getKnownServices())
				{
					try
					{
						aClass.getField("B_STATS_VERSION");
						found = true;
						break;
					}
					catch (NoSuchFieldException ignored)
					{
					}
				}

				Bukkit.getServicesManager().register(
					Metrics.class,
					this,
					plugin,
					ServicePriority.Normal
				);

				if (!found) {
					this.startSubmitting();
				}
			}
		}
	}

	private static void sendData(JSONObject data) throws Exception
	{
		if (data == null)
		{
			throw new IllegalArgumentException("Data cannot be null!");
		}

		if (Bukkit.isPrimaryThread())
		{
			throw new IllegalAccessException("This method must not be called from the main thread!");
		}
		else
		{
			HttpsURLConnection connection = (HttpsURLConnection)(new URL(URL)).openConnection();
			byte[] compressedData = compress(data.toString());
			connection.setRequestMethod("POST");
			connection.addRequestProperty("Accept", "application/json");
			connection.addRequestProperty("Connection", "close");
			connection.addRequestProperty("Content-Encoding", "gzip");
			connection.addRequestProperty("Content-Length", String.valueOf(compressedData.length));
			connection.setRequestProperty("Content-Type", "application/json");
			connection.setRequestProperty("User-Agent", "MC-Server/" + B_STATS_VERSION);

			connection.setDoOutput(true);
			DataOutputStream outputStream = new DataOutputStream(connection.getOutputStream());
			outputStream.write(compressedData);
			outputStream.flush();
			outputStream.close();

			connection.getInputStream().close();
		}
	}

	private static byte[] compress(String str) throws IOException {
		if (str == null)
		{
			return null;
		}
		else
		{
			ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
			GZIPOutputStream gzip = new GZIPOutputStream(outputStream);
			gzip.write(str.getBytes(StandardCharsets.UTF_8));
			gzip.close();
			return outputStream.toByteArray();
		}
	}

	void addCustomChart(Metrics.CustomChart chart) {
		if (chart == null)
		{
			throw new IllegalArgumentException("Chart cannot be null!");
		}
		else
		{
			charts.add(chart);
		}
	}

	private void startSubmitting() {
		final Timer timer = new Timer(true);
		timer.scheduleAtFixedRate(new TimerTask() {
			public void run() {
				if (!plugin.isEnabled()) {
					timer.cancel();
				}
				else
				{
					Bukkit.getScheduler().runTask(plugin, () -> submitData());
				}
			}
		}, 1000 * 60 * 5, 1000 * 60 * 30);
	}


	@SuppressWarnings("unused")
	public JSONObject getPluginData() {
		JSONObject data = new JSONObject();

		String pluginName = this.plugin.getDescription().getName();
		String pluginVersion = this.plugin.getDescription().getVersion();

		data.put("pluginName", pluginName);
		data.put("pluginVersion", pluginVersion);
		JSONArray customCharts = new JSONArray();

		for (CustomChart customChart : charts)
		{
			JSONObject chart = customChart.getRequestJsonObject();
			if (chart != null)
			{
				customCharts.put(chart);
			}
		}

		data.put("customCharts", customCharts);
		return data;
	}

	private JSONObject getServerData() {
		int playerAmount = Bukkit.getOnlinePlayers().size();
		int onlineMode = Bukkit.getOnlineMode() ? 1 : 0;
		String bukkitVersion = Bukkit.getVersion();
		bukkitVersion = bukkitVersion.substring(bukkitVersion.indexOf("MC: ") + 4, bukkitVersion.length() - 1);
		String javaVersion = System.getProperty("java.version");
		String osName = System.getProperty("os.name");
		String osArch = System.getProperty("os.arch");
		String osVersion = System.getProperty("os.version");
		int coreCount = Runtime.getRuntime().availableProcessors();
		JSONObject data = new JSONObject();
		data.put("serverUUID", serverUUID);
		data.put("playerAmount", playerAmount);
		data.put("onlineMode", onlineMode);
		data.put("bukkitVersion", bukkitVersion);
		data.put("javaVersion", javaVersion);
		data.put("osName", osName);
		data.put("osArch", osArch);
		data.put("osVersion", osVersion);
		data.put("coreCount", coreCount);
		return data;
	}

	private void submitData() {
		final JSONObject data = this.getServerData();
		JSONArray pluginData = new JSONArray();

		for (Class<?> service : Bukkit.getServicesManager().getKnownServices())
		{
			try
			{
				service.getField("B_STATS_VERSION");
			}
			catch (NoSuchFieldException e)
			{
				continue;
			}

			try
			{
				pluginData.put(service.getMethod("getPluginData").invoke(Bukkit.getServicesManager().load(service)));
			}
			catch (IllegalAccessException | InvocationTargetException | NoSuchMethodException ignored)
			{
			}
		}

		data.put("plugins", pluginData);
		new Thread(()-> {
			try {
				sendData(data);
			} catch (Exception e) {
				if (logFailedRequests) {
					plugin.getLogger().log(
						Level.WARNING,
						"Could not submit plugin stats of " + plugin.getName(),
						e
					);
				}
			}
		}).start();
	}

	/**
	 * Represents a custom advanced map chart.
	 */
	@SuppressWarnings({"unused", "WeakerAccess"})
	public static abstract class AdvancedMapChart extends CustomChart {

		/**
		 * Class constructor.
		 *
		 * @param chartId The id of the chart.
		 */
		public AdvancedMapChart(String chartId) {
			super(chartId);
		}

		/**
		 * Gets the value of the chart.
		 *
		 * @param valueMap Just an empty map. The only reason it exists is to make your life easier.
		 *                 You don't have to create a map yourself!
		 * @return The value of the chart.
		 */
		public abstract HashMap<Country, Integer> getValues(HashMap<Country, Integer> valueMap);

		@Override
		protected JSONObject getChartData() {
			JSONObject data = new JSONObject();
			JSONObject values = new JSONObject();
			HashMap<Country, Integer> map = getValues(new HashMap<>());
			if (map == null || map.isEmpty()) {
				// Null = skip the chart
				return null;
			}
			boolean allSkipped = true;
			for (Map.Entry<Country, Integer> entry : map.entrySet()) {
				if (entry.getValue() == 0) {
					continue; // Skip this invalid
				}
				allSkipped = false;
				values.put(entry.getKey().getCountryIsoTag(), entry.getValue());
			}
			if (allSkipped) {
				// Null = skip the chart
				return null;
			}
			data.put("values", values);
			return data;
		}
	}

	/**
	 * Represents a custom simple map chart.
	 */
	@SuppressWarnings({"unused", "WeakerAccess"})
	public static abstract class SimpleMapChart extends CustomChart {

		/**
		 * Class constructor.
		 *
		 * @param chartId The id of the chart.
		 */
		public SimpleMapChart(String chartId) {
			super(chartId);
		}

		/**
		 * Gets the value of the chart.
		 *
		 * @return The value of the chart.
		 */
		public abstract Country getValue();

		@Override
		protected JSONObject getChartData() {
			JSONObject data = new JSONObject();
			Country value = getValue();

			if (value == null) {
				// Null = skip the chart
				return null;
			}
			data.put("value", value.getCountryIsoTag());
			return data;
		}
	}

	/**
	 * Represents a custom advanced bar chart.
	 */
	@SuppressWarnings({"unused", "WeakerAccess"})
	public static abstract class AdvancedBarChart extends CustomChart {

		/**
		 * Class constructor.
		 *
		 * @param chartId The id of the chart.
		 */
		public AdvancedBarChart(String chartId) {
			super(chartId);
		}

		/**
		 * Gets the value of the chart.
		 *
		 * @param valueMap Just an empty map. The only reason it exists is to make your life easier.
		 *                 You don't have to create a map yourself!
		 * @return The value of the chart.
		 */
		public abstract HashMap<String, int[]> getValues(HashMap<String, int[]> valueMap);

		@Override
		protected JSONObject getChartData() {
			JSONObject data = new JSONObject();
			JSONObject values = new JSONObject();
			HashMap<String, int[]> map = getValues(new HashMap<>());
			if (map == null || map.isEmpty()) {
				// Null = skip the chart
				return null;
			}
			boolean allSkipped = true;
			for (Map.Entry<String, int[]> entry : map.entrySet()) {
				if (entry.getValue().length == 0) {
					continue; // Skip this invalid
				}
				allSkipped = false;
				JSONArray categoryValues = new JSONArray();
				for (int categoryValue : entry.getValue()) {
					categoryValues.put(categoryValue);
				}
				values.put(entry.getKey(), categoryValues);
			}
			if (allSkipped) {
				// Null = skip the chart
				return null;
			}
			data.put("values", values);
			return data;
		}

	}

	/**
	 * Represents a custom simple bar chart.
	 */
	@SuppressWarnings({"unused", "WeakerAccess"})
	public static abstract class SimpleBarChart extends CustomChart {

		/**
		 * Class constructor.
		 *
		 * @param chartId The id of the chart.
		 */
		public SimpleBarChart(String chartId) {
			super(chartId);
		}

		/**
		 * Gets the value of the chart.
		 *
		 * @param valueMap Just an empty map. The only reason it exists is to make your life easier.
		 *                 You don't have to create a map yourself!
		 * @return The value of the chart.
		 */
		public abstract HashMap<String, Integer> getValues(HashMap<String, Integer> valueMap);

		@Override
		protected JSONObject getChartData() {
			JSONObject data = new JSONObject();
			JSONObject values = new JSONObject();
			HashMap<String, Integer> map = getValues(new HashMap<>());
			if (map == null || map.isEmpty()) {
				// Null = skip the chart
				return null;
			}
			for (Map.Entry<String, Integer> entry : map.entrySet()) {
				JSONArray categoryValues = new JSONArray();
				categoryValues.put(entry.getValue());
				values.put(entry.getKey(), categoryValues);
			}
			data.put("values", values);
			return data;
		}

	}

	/**
	 * Represents a custom multi line chart.
	 */
	@SuppressWarnings({"unused", "WeakerAccess"})
	public static abstract class MultiLineChart extends CustomChart {

		/**
		 * Class constructor.
		 *
		 * @param chartId The id of the chart.
		 */
		public MultiLineChart(String chartId) {
			super(chartId);
		}

		/**
		 * Gets the values of the chart.
		 *
		 * @param valueMap Just an empty map. The only reason it exists is to make your life easier.
		 *                 You don't have to create a map yourself!
		 * @return The values of the chart.
		 */
		public abstract HashMap<String, Integer> getValues(HashMap<String, Integer> valueMap);

		@Override
		protected JSONObject getChartData() {
			JSONObject data = new JSONObject();
			JSONObject values = new JSONObject();
			HashMap<String, Integer> map = getValues(new HashMap<>());
			if (map == null || map.isEmpty()) {
				// Null = skip the chart
				return null;
			}
			boolean allSkipped = true;
			for (Map.Entry<String, Integer> entry : map.entrySet()) {
				if (entry.getValue() == 0) {
					continue; // Skip this invalid
				}
				allSkipped = false;
				values.put(entry.getKey(), entry.getValue());
			}
			if (allSkipped) {
				// Null = skip the chart
				return null;
			}
			data.put("values", values);
			return data;
		}

	}

	public abstract static class SingleLineChart extends Metrics.CustomChart {
		SingleLineChart(String chartId) {
			super(chartId);
		}

		public abstract int getValue();

		protected JSONObject getChartData() {
			JSONObject data = new JSONObject();
			int value = this.getValue();
			if (value == 0) {
				return null;
			} else {
				data.put("value", value);
				return data;
			}
		}
	}

	/**
	 * Represents a custom advanced pie.
	 */
	@SuppressWarnings({"unused", "WeakerAccess"})
	public static abstract class AdvancedPie extends CustomChart {

		/**
		 * Class constructor.
		 *
		 * @param chartId The id of the chart.
		 */
		public AdvancedPie(String chartId) {
			super(chartId);
		}

		/**
		 * Gets the values of the pie.
		 *
		 * @param valueMap Just an empty map. The only reason it exists is to make your life easier.
		 *                 You don't have to create a map yourself!
		 * @return The values of the pie.
		 */
		public abstract HashMap<String, Integer> getValues(HashMap<String, Integer> valueMap);

		@Override
		protected JSONObject getChartData() {
			JSONObject data = new JSONObject();
			JSONObject values = new JSONObject();
			HashMap<String, Integer> map = getValues(new HashMap<>());
			if (map == null || map.isEmpty()) {
				// Null = skip the chart
				return null;
			}
			boolean allSkipped = true;
			for (Map.Entry<String, Integer> entry : map.entrySet()) {
				if (entry.getValue() == 0) {
					continue; // Skip this invalid
				}
				allSkipped = false;
				values.put(entry.getKey(), entry.getValue());
			}
			if (allSkipped) {
				// Null = skip the chart
				return null;
			}
			data.put("values", values);
			return data;
		}
	}


	/**
	 * Represents a custom simple pie.
	 */
	@SuppressWarnings({"unused", "WeakerAccess"})
	public static abstract class SimplePie extends CustomChart {

		/**
		 * Class constructor.
		 *
		 * @param chartId The id of the chart.
		 */
		public SimplePie(String chartId) {
			super(chartId);
		}

		/**
		 * Gets the value of the pie.
		 *
		 * @return The value of the pie.
		 */
		public abstract String getValue();

		@Override
		protected JSONObject getChartData() {
			JSONObject data = new JSONObject();
			String value = getValue();
			if (value == null || value.isEmpty()) {
				// Null = skip the chart
				return null;
			}
			data.put("value", value);
			return data;
		}
	}


	/**
	 * Represents a custom chart.
	 */
	@SuppressWarnings("WeakerAccess")
	public static abstract class CustomChart {

		// The id of the chart
		protected final String chartId;

		/**
		 * Class constructor.
		 *
		 * @param chartId The id of the chart.
		 */
		public CustomChart(String chartId) {
			if (chartId == null || chartId.isEmpty()) {
				throw new IllegalArgumentException("ChartId cannot be null or empty!");
			}
			this.chartId = chartId;
		}

		protected JSONObject getRequestJsonObject() {
			JSONObject chart = new JSONObject();
			chart.put("chartId", chartId);
			try {
				JSONObject data = getChartData();
				if (data == null) {
					// If the data is null we don't send the chart.
					return null;
				}
				chart.put("data", data);
			} catch (Throwable t) {
				if (logFailedRequests) {
					Bukkit.getLogger().log(Level.WARNING, "Failed to get data for custom chart with id " + chartId, t);
				}
				return null;
			}
			return chart;
		}

		protected abstract JSONObject getChartData();

	}

	/**
	 * A enum which is used for custom maps.
	 */
	@SuppressWarnings("unused")
	public enum Country {

		/**
		 * bStats will use the country of the server.
		 */
		AUTO_DETECT("AUTO", "Auto Detected"),

		ANDORRA("AD", "Andorra"),
		UNITED_ARAB_EMIRATES("AE", "United Arab Emirates"),
		AFGHANISTAN("AF", "Afghanistan"),
		ANTIGUA_AND_BARBUDA("AG", "Antigua and Barbuda"),
		ANGUILLA("AI", "Anguilla"),
		ALBANIA("AL", "Albania"),
		ARMENIA("AM", "Armenia"),
		NETHERLANDS_ANTILLES("AN", "Netherlands Antilles"),
		ANGOLA("AO", "Angola"),
		ANTARCTICA("AQ", "Antarctica"),
		ARGENTINA("AR", "Argentina"),
		AMERICAN_SAMOA("AS", "American Samoa"),
		AUSTRIA("AT", "Austria"),
		AUSTRALIA("AU", "Australia"),
		ARUBA("AW", "Aruba"),
		ALAND_ISLANDS("AX", "Åland Islands"),
		AZERBAIJAN("AZ", "Azerbaijan"),
		BOSNIA_AND_HERZEGOVINA("BA", "Bosnia and Herzegovina"),
		BARBADOS("BB", "Barbados"),
		BANGLADESH("BD", "Bangladesh"),
		BELGIUM("BE", "Belgium"),
		BURKINA_FASO("BF", "Burkina Faso"),
		BULGARIA("BG", "Bulgaria"),
		BAHRAIN("BH", "Bahrain"),
		BURUNDI("BI", "Burundi"),
		BENIN("BJ", "Benin"),
		SAINT_BARTHELEMY("BL", "Saint Barthélemy"),
		BERMUDA("BM", "Bermuda"),
		BRUNEI("BN", "Brunei"),
		BOLIVIA("BO", "Bolivia"),
		BONAIRE_SINT_EUSTATIUS_AND_SABA("BQ", "Bonaire, Sint Eustatius and Saba"),
		BRAZIL("BR", "Brazil"),
		BAHAMAS("BS", "Bahamas"),
		BHUTAN("BT", "Bhutan"),
		BOUVET_ISLAND("BV", "Bouvet Island"),
		BOTSWANA("BW", "Botswana"),
		BELARUS("BY", "Belarus"),
		BELIZE("BZ", "Belize"),
		CANADA("CA", "Canada"),
		COCOS_ISLANDS("CC", "Cocos Islands"),
		THE_DEMOCRATIC_REPUBLIC_OF_CONGO("CD", "The Democratic Republic Of Congo"),
		CENTRAL_AFRICAN_REPUBLIC("CF", "Central African Republic"),
		CONGO("CG", "Congo"),
		SWITZERLAND("CH", "Switzerland"),
		COTE_D_IVOIRE("CI", "Côte d'Ivoire"),
		COOK_ISLANDS("CK", "Cook Islands"),
		CHILE("CL", "Chile"),
		CAMEROON("CM", "Cameroon"),
		CHINA("CN", "China"),
		COLOMBIA("CO", "Colombia"),
		COSTA_RICA("CR", "Costa Rica"),
		CUBA("CU", "Cuba"),
		CAPE_VERDE("CV", "Cape Verde"),
		CURACAO("CW", "Curaçao"),
		CHRISTMAS_ISLAND("CX", "Christmas Island"),
		CYPRUS("CY", "Cyprus"),
		CZECH_REPUBLIC("CZ", "Czech Republic"),
		GERMANY("DE", "Germany"),
		DJIBOUTI("DJ", "Djibouti"),
		DENMARK("DK", "Denmark"),
		DOMINICA("DM", "Dominica"),
		DOMINICAN_REPUBLIC("DO", "Dominican Republic"),
		ALGERIA("DZ", "Algeria"),
		ECUADOR("EC", "Ecuador"),
		ESTONIA("EE", "Estonia"),
		EGYPT("EG", "Egypt"),
		WESTERN_SAHARA("EH", "Western Sahara"),
		ERITREA("ER", "Eritrea"),
		SPAIN("ES", "Spain"),
		ETHIOPIA("ET", "Ethiopia"),
		FINLAND("FI", "Finland"),
		FIJI("FJ", "Fiji"),
		FALKLAND_ISLANDS("FK", "Falkland Islands"),
		MICRONESIA("FM", "Micronesia"),
		FAROE_ISLANDS("FO", "Faroe Islands"),
		FRANCE("FR", "France"),
		GABON("GA", "Gabon"),
		UNITED_KINGDOM("GB", "United Kingdom"),
		GRENADA("GD", "Grenada"),
		GEORGIA("GE", "Georgia"),
		FRENCH_GUIANA("GF", "French Guiana"),
		GUERNSEY("GG", "Guernsey"),
		GHANA("GH", "Ghana"),
		GIBRALTAR("GI", "Gibraltar"),
		GREENLAND("GL", "Greenland"),
		GAMBIA("GM", "Gambia"),
		GUINEA("GN", "Guinea"),
		GUADELOUPE("GP", "Guadeloupe"),
		EQUATORIAL_GUINEA("GQ", "Equatorial Guinea"),
		GREECE("GR", "Greece"),
		SOUTH_GEORGIA_AND_THE_SOUTH_SANDWICH_ISLANDS("GS", "South Georgia And The South Sandwich Islands"),
		GUATEMALA("GT", "Guatemala"),
		GUAM("GU", "Guam"),
		GUINEA_BISSAU("GW", "Guinea-Bissau"),
		GUYANA("GY", "Guyana"),
		HONG_KONG("HK", "Hong Kong"),
		HEARD_ISLAND_AND_MCDONALD_ISLANDS("HM", "Heard Island And McDonald Islands"),
		HONDURAS("HN", "Honduras"),
		CROATIA("HR", "Croatia"),
		HAITI("HT", "Haiti"),
		HUNGARY("HU", "Hungary"),
		INDONESIA("ID", "Indonesia"),
		IRELAND("IE", "Ireland"),
		ISRAEL("IL", "Israel"),
		ISLE_OF_MAN("IM", "Isle Of Man"),
		INDIA("IN", "India"),
		BRITISH_INDIAN_OCEAN_TERRITORY("IO", "British Indian Ocean Territory"),
		IRAQ("IQ", "Iraq"),
		IRAN("IR", "Iran"),
		ICELAND("IS", "Iceland"),
		ITALY("IT", "Italy"),
		JERSEY("JE", "Jersey"),
		JAMAICA("JM", "Jamaica"),
		JORDAN("JO", "Jordan"),
		JAPAN("JP", "Japan"),
		KENYA("KE", "Kenya"),
		KYRGYZSTAN("KG", "Kyrgyzstan"),
		CAMBODIA("KH", "Cambodia"),
		KIRIBATI("KI", "Kiribati"),
		COMOROS("KM", "Comoros"),
		SAINT_KITTS_AND_NEVIS("KN", "Saint Kitts And Nevis"),
		NORTH_KOREA("KP", "North Korea"),
		SOUTH_KOREA("KR", "South Korea"),
		KUWAIT("KW", "Kuwait"),
		CAYMAN_ISLANDS("KY", "Cayman Islands"),
		KAZAKHSTAN("KZ", "Kazakhstan"),
		LAOS("LA", "Laos"),
		LEBANON("LB", "Lebanon"),
		SAINT_LUCIA("LC", "Saint Lucia"),
		LIECHTENSTEIN("LI", "Liechtenstein"),
		SRI_LANKA("LK", "Sri Lanka"),
		LIBERIA("LR", "Liberia"),
		LESOTHO("LS", "Lesotho"),
		LITHUANIA("LT", "Lithuania"),
		LUXEMBOURG("LU", "Luxembourg"),
		LATVIA("LV", "Latvia"),
		LIBYA("LY", "Libya"),
		MOROCCO("MA", "Morocco"),
		MONACO("MC", "Monaco"),
		MOLDOVA("MD", "Moldova"),
		MONTENEGRO("ME", "Montenegro"),
		SAINT_MARTIN("MF", "Saint Martin"),
		MADAGASCAR("MG", "Madagascar"),
		MARSHALL_ISLANDS("MH", "Marshall Islands"),
		MACEDONIA("MK", "Macedonia"),
		MALI("ML", "Mali"),
		MYANMAR("MM", "Myanmar"),
		MONGOLIA("MN", "Mongolia"),
		MACAO("MO", "Macao"),
		NORTHERN_MARIANA_ISLANDS("MP", "Northern Mariana Islands"),
		MARTINIQUE("MQ", "Martinique"),
		MAURITANIA("MR", "Mauritania"),
		MONTSERRAT("MS", "Montserrat"),
		MALTA("MT", "Malta"),
		MAURITIUS("MU", "Mauritius"),
		MALDIVES("MV", "Maldives"),
		MALAWI("MW", "Malawi"),
		MEXICO("MX", "Mexico"),
		MALAYSIA("MY", "Malaysia"),
		MOZAMBIQUE("MZ", "Mozambique"),
		NAMIBIA("NA", "Namibia"),
		NEW_CALEDONIA("NC", "New Caledonia"),
		NIGER("NE", "Niger"),
		NORFOLK_ISLAND("NF", "Norfolk Island"),
		NIGERIA("NG", "Nigeria"),
		NICARAGUA("NI", "Nicaragua"),
		NETHERLANDS("NL", "Netherlands"),
		NORWAY("NO", "Norway"),
		NEPAL("NP", "Nepal"),
		NAURU("NR", "Nauru"),
		NIUE("NU", "Niue"),
		NEW_ZEALAND("NZ", "New Zealand"),
		OMAN("OM", "Oman"),
		PANAMA("PA", "Panama"),
		PERU("PE", "Peru"),
		FRENCH_POLYNESIA("PF", "French Polynesia"),
		PAPUA_NEW_GUINEA("PG", "Papua New Guinea"),
		PHILIPPINES("PH", "Philippines"),
		PAKISTAN("PK", "Pakistan"),
		POLAND("PL", "Poland"),
		SAINT_PIERRE_AND_MIQUELON("PM", "Saint Pierre And Miquelon"),
		PITCAIRN("PN", "Pitcairn"),
		PUERTO_RICO("PR", "Puerto Rico"),
		PALESTINE("PS", "Palestine"),
		PORTUGAL("PT", "Portugal"),
		PALAU("PW", "Palau"),
		PARAGUAY("PY", "Paraguay"),
		QATAR("QA", "Qatar"),
		REUNION("RE", "Reunion"),
		ROMANIA("RO", "Romania"),
		SERBIA("RS", "Serbia"),
		RUSSIA("RU", "Russia"),
		RWANDA("RW", "Rwanda"),
		SAUDI_ARABIA("SA", "Saudi Arabia"),
		SOLOMON_ISLANDS("SB", "Solomon Islands"),
		SEYCHELLES("SC", "Seychelles"),
		SUDAN("SD", "Sudan"),
		SWEDEN("SE", "Sweden"),
		SINGAPORE("SG", "Singapore"),
		SAINT_HELENA("SH", "Saint Helena"),
		SLOVENIA("SI", "Slovenia"),
		SVALBARD_AND_JAN_MAYEN("SJ", "Svalbard And Jan Mayen"),
		SLOVAKIA("SK", "Slovakia"),
		SIERRA_LEONE("SL", "Sierra Leone"),
		SAN_MARINO("SM", "San Marino"),
		SENEGAL("SN", "Senegal"),
		SOMALIA("SO", "Somalia"),
		SURINAME("SR", "Suriname"),
		SOUTH_SUDAN("SS", "South Sudan"),
		SAO_TOME_AND_PRINCIPE("ST", "Sao Tome And Principe"),
		EL_SALVADOR("SV", "El Salvador"),
		SINT_MAARTEN_DUTCH_PART("SX", "Sint Maarten (Dutch part)"),
		SYRIA("SY", "Syria"),
		SWAZILAND("SZ", "Swaziland"),
		TURKS_AND_CAICOS_ISLANDS("TC", "Turks And Caicos Islands"),
		CHAD("TD", "Chad"),
		FRENCH_SOUTHERN_TERRITORIES("TF", "French Southern Territories"),
		TOGO("TG", "Togo"),
		THAILAND("TH", "Thailand"),
		TAJIKISTAN("TJ", "Tajikistan"),
		TOKELAU("TK", "Tokelau"),
		TIMOR_LESTE("TL", "Timor-Leste"),
		TURKMENISTAN("TM", "Turkmenistan"),
		TUNISIA("TN", "Tunisia"),
		TONGA("TO", "Tonga"),
		TURKEY("TR", "Turkey"),
		TRINIDAD_AND_TOBAGO("TT", "Trinidad and Tobago"),
		TUVALU("TV", "Tuvalu"),
		TAIWAN("TW", "Taiwan"),
		TANZANIA("TZ", "Tanzania"),
		UKRAINE("UA", "Ukraine"),
		UGANDA("UG", "Uganda"),
		UNITED_STATES_MINOR_OUTLYING_ISLANDS("UM", "United States Minor Outlying Islands"),
		UNITED_STATES("US", "United States"),
		URUGUAY("UY", "Uruguay"),
		UZBEKISTAN("UZ", "Uzbekistan"),
		VATICAN("VA", "Vatican"),
		SAINT_VINCENT_AND_THE_GRENADINES("VC", "Saint Vincent And The Grenadines"),
		VENEZUELA("VE", "Venezuela"),
		BRITISH_VIRGIN_ISLANDS("VG", "British Virgin Islands"),
		U_S__VIRGIN_ISLANDS("VI", "U.S. Virgin Islands"),
		VIETNAM("VN", "Vietnam"),
		VANUATU("VU", "Vanuatu"),
		WALLIS_AND_FUTUNA("WF", "Wallis And Futuna"),
		SAMOA("WS", "Samoa"),
		YEMEN("YE", "Yemen"),
		MAYOTTE("YT", "Mayotte"),
		SOUTH_AFRICA("ZA", "South Africa"),
		ZAMBIA("ZM", "Zambia"),
		ZIMBABWE("ZW", "Zimbabwe");

		private String isoTag;
		private String name;

		Country(String isoTag, String name) {
			this.isoTag = isoTag;
			this.name = name;
		}

		/**
		 * Gets a country by it's iso tag.
		 *
		 * @param isoTag The iso tag of the county.
		 * @return The country with the given iso tag or <code>null</code> if unknown.
		 */
		public static Country byIsoTag(String isoTag) {
			for (Country country : Country.values()) {
				if (country.getCountryIsoTag().equals(isoTag)) {
					return country;
				}
			}
			return null;
		}

		/**
		 * Gets a country by a locale.
		 *
		 * @param locale The locale.
		 * @return The country from the giben locale or <code>null</code> if unknown country or
		 * if the locale does not contain a country.
		 */
		public static Country byLocale(Locale locale) {
			return byIsoTag(locale.getCountry());
		}

		/**
		 * Gets the name of the country.
		 *
		 * @return The name of the country.
		 */
		public String getCountryName() {
			return name;
		}

		/**
		 * Gets the iso tag of the country.
		 *
		 * @return The iso tag of the country.
		 */
		public String getCountryIsoTag() {
			return isoTag;
		}

	}
}
