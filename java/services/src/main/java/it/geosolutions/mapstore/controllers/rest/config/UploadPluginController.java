/*
 * Copyright 2020, GeoSolutions Sas.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree.
*/
package it.geosolutions.mapstore.controllers.rest.config;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.servlet.ServletContext;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.annotation.Secured;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import it.geosolutions.mapstore.controllers.BaseMapStoreController;
import it.geosolutions.mapstore.utils.ResourceUtils;

/**
 * REST service used to upload (install) or uninstall extensions.
 * When a plugin is installed, this class takes care of :
 * - Storing the data in the proper folder (data-dir or webpapp), in `extensionsFolder`.
 * - Modifying `pluginsConfig.json` and `extensions.json` (in `extensionsFolder). to include the new plugin.
 * - When a datadir is available, the pluginsConfig.json original file is not touched, a `pluginsConfig.json.patch` file is used instead
 *   in json-patch format to list only the uploaded extensions.
 * On uninstall, the class will clean up the files and the directories above to remove the plugins.
 * TODO: move this in extensions package (and services path, aligning the client)
 */
@Controller
public class UploadPluginController extends BaseMapStoreController {

	private ObjectMapper jsonMapper = new ObjectMapper();
	private JsonNodeFactory jsonNodeFactory = new JsonNodeFactory(false);

	@Autowired
	ServletContext context;

	/**
	 * Stores and uploaded plugin zip bundle. The zip bundle must be POSTed as the
	 * body of the request. The content of the bundle will be handled as follows: -
	 * javascript files (compiled bundles) will be stored in extensions.folder (in a
	 * subfolder with the extension name) - assets files (translations) will be
	 * stored in extensions.folder (in the translations folder of a subfolder with
	 * the extension name) - the extensions registry file will be updated with data
	 * read from the zip index.json file - the context creator plugins file will be
	 * updated with data read from the zip index.json file
	 */
	@Secured({ "ROLE_ADMIN" })
	@RequestMapping(value = "/uploadPlugin", method = RequestMethod.POST, headers = "Accept=application/json")
	public @ResponseBody String uploadPlugin(InputStream dataStream) throws IOException {
		ZipInputStream zip = new ZipInputStream(dataStream);
		ZipEntry entry = zip.getNextEntry();
		String pluginName = null;
		;
		String bundleName = null;
		Map<File, String> tempFiles = new HashMap<File, String>();
		JsonNode plugin = null;
		boolean addTranslations = false;
		while (entry != null) {
			if (!entry.isDirectory()) {
				if (entry.getName().toLowerCase().endsWith("index.js")) {
					bundleName = entry.getName();
					File tempBundle = File.createTempFile("mapstore-bundle", ".js");
					storeAsset(zip, tempBundle);
					tempFiles.put(tempBundle, "js");
				}
				if ("index.json".equals(entry.getName().toLowerCase())) {
					JsonNode json = readJSON(zip);
					JsonNode plugins = json.get("plugins");
					// TODO: add support for many plugins in one single extension
					plugin = plugins.get(0);
					((ObjectNode) plugin).put("extension", true);
					pluginName = plugin.get("name").asText();
					if (shouldStorePluginsConfigAsPatch()) {
						addPluginConfigurationAsPatch(plugin);
					} else {
						addPluginConfiguration(plugin);
					}
				}
				if (entry.getName().toLowerCase().startsWith("translations/")) {
					File tempAsset = File.createTempFile("mapstore-asset-translations", ".json");
					storeAsset(zip, tempAsset);
					tempFiles.put(tempAsset, "asset/" + entry.getName());
					addTranslations = true;
				}
				// all files inside assets directory must be added to assets
				if (entry.getName().toLowerCase().startsWith("assets/")) {
					File tempAsset = File.createTempFile(entry.getName(), ".tmp");
					storeAsset(zip, tempAsset);
					tempFiles.put(tempAsset, "asset/" + entry.getName());
				}
			}
			entry = zip.getNextEntry();
		}
		String pluginBundle = pluginName + "/" + bundleName;
		String translations = addTranslations ? pluginName + "/translations" : null;
		addExtension(pluginName, pluginBundle, translations);
		for (File tempFile : tempFiles.keySet()) {
			String type = tempFiles.get(tempFile);
			if ("js".equals(type)) {
				moveAsset(tempFile, getExtensionsFolder() + "/" + pluginBundle);
			}
			if (type.indexOf("asset/") == 0) {
				String assetPath = getExtensionsFolder() + "/" + pluginName + "/"
						+ type.substring(type.indexOf("/") + 1);
				moveAsset(tempFile, assetPath);
			}
		}

		zip.close();
		if (plugin == null) {
			throw new IOException("Invalid bundle: index.json missing");
		}
		return plugin.toString();
	}

	private boolean shouldStorePluginsConfigAsPatch() {
		// we use patch files only if we have a datadir
		return getPluginsConfigAsPatch() && canUseDataDir();
	}

	private boolean canUseDataDir() {
		return getDataDir().isEmpty() ? false : Stream.of(getDataDir().split(",")).filter(new Predicate<String>() {
			@Override
			public boolean test(String folder) {
				return !folder.trim().isEmpty() && new File(folder).exists();
			}
		}).count() > 0;

	}

	/**
	 * Removes an installed plugin extension.
	 *
	 * @param pluginName name of the extension to be removed
	 */
    @Secured({ "ROLE_ADMIN" })
    @RequestMapping(value = "/uninstallPlugin/{pluginName}", method = RequestMethod.DELETE)
    public @ResponseBody String uninstallPlugin(@PathVariable String pluginName) throws IOException {
        // Basic validation to avoid path traversal characters in pluginName
        if (pluginName.contains("..") || pluginName.contains("\\")) {
            throw new IllegalArgumentException("Invalid plugin name.");
        }

        ObjectNode configObj = getExtensionConfig();
        if (configObj.has(pluginName)) {
            JsonNode pluginConfig = configObj.get(pluginName);
            String pluginBundle = pluginConfig.get("bundle").asText();
            String pluginFolder = pluginBundle.substring(0, pluginBundle.lastIndexOf("/"));

            // Securely remove the folder by passing normalized path
            Path pluginFolderPath = Paths.get(getExtensionsFolder(), pluginFolder).normalize();
            removeFolderSecurely(pluginFolderPath);

            // Update configurations after removing the folder
            configObj.remove(pluginName);
            storeJSONConfig(configObj, getExtensionsConfigPath());

            ObjectNode pluginsConfigObj = null;
            ArrayNode plugins;
            if (shouldStorePluginsConfigAsPatch()) {
                plugins = getPluginsConfigurationPatch();
            } else {
                pluginsConfigObj = getPluginsConfiguration();
                plugins = (ArrayNode) pluginsConfigObj.get("plugins");
            }

            int toRemove = -1;
            for (int i = 0; i < plugins.size(); i++) {
                JsonNode plugin = plugins.get(i);
                String name = plugin.has("name") ? plugin.get("name").asText()
                    : plugin.get("value").get("name").asText();
                if (name.contentEquals(pluginName)) {
                    toRemove = i;
                }
            }

            if (toRemove >= 0) {
                plugins.remove(toRemove);
            }

            if (shouldStorePluginsConfigAsPatch()) {
                storeJSONConfig(plugins, getPluginsConfigPatchFilePath());
            } else {
                storeJSONConfig(pluginsConfigObj, getPluginsConfig());
            }

            return pluginConfig.toString();
        } else {
            return "{}";
        }
    }

    // Updated removeFolder method with path traversal prevention
    private void removeFolderSecurely(Path pluginFolderPath) throws IOException {
        if (pluginFolderPath == null) {
            throw new IllegalArgumentException("Plugin folder path cannot be null.");
        }

        // Define base directory to ensure path remains within it
        Path baseDirectory = Paths.get(getExtensionsFolder()).toAbsolutePath().normalize();
        if (baseDirectory == null) {
            throw new IllegalStateException("Extensions folder path is not set correctly.");
        }

        Path fullPath = pluginFolderPath.toAbsolutePath().normalize();

        // Ensure the path is within the base directory
        if (!fullPath.startsWith(baseDirectory)) {
            throw new IOException("Unauthorized path traversal attempt detected.");
        }

        File folderPath = new File(ResourceUtils.getResourcePath(getWriteStorage(), context, pluginFolderPath.toString()));
        if (folderPath.exists()) {
            FileUtils.cleanDirectory(folderPath);
            folderPath.delete();
        } else {
            throw new FileNotFoundException("The specified folder path does not exist: " + folderPath.getAbsolutePath());
        }
    }

    private Optional<File> findResource(String resourceName) {
		return ResourceUtils.findResource(getDataDir(), context, resourceName);
	}

    private void moveAsset(File tempAsset, String finalAsset) throws FileNotFoundException, IOException {
        String assetPath = ResourceUtils.getResourcePath(getWriteStorage(), context, finalAsset);

        // Check if the resource path is null and handle it
        if (assetPath == null) {
            throw new FileNotFoundException("Resource path could not be resolved for: " + finalAsset);
        }

        // Ensure the parent directory exists
        new File(assetPath).getParentFile().mkdirs();

        try (FileInputStream input = new FileInputStream(tempAsset);
             FileOutputStream output = new FileOutputStream(assetPath)) {
            IOUtils.copy(input, output);
        }
        tempAsset.delete();
    }

    private String getWriteStorage() {
		return getDataDir().isEmpty() ? "" : Stream.of(getDataDir().split(",")).filter(new Predicate<String>() {
			@Override
			public boolean test(String folder) {
				return !folder.trim().isEmpty();
			}
		}).findFirst().orElse("");
	}

	private void addPluginConfiguration(JsonNode json) throws IOException {
		ObjectNode config = null;
		Optional<File> pluginsConfigFile = findResource(getPluginsConfigPath());
		if (pluginsConfigFile.isPresent()) {
			try (FileInputStream input = new FileInputStream(pluginsConfigFile.get())) {
				config = (ObjectNode) readJSON(input);
			} catch (FileNotFoundException e) {
				config = jsonNodeFactory.objectNode();
				config.set("plugins", jsonNodeFactory.arrayNode());
			}
		} else {
			config = jsonNodeFactory.objectNode();
			config.set("plugins", jsonNodeFactory.arrayNode());
		}
		if (config != null) {
			ArrayNode plugins = (ArrayNode) config.get("plugins");
			int remove = -1;
			for (int count = 0; count < plugins.size(); count++) {
				JsonNode node = plugins.get(count);
				if (json.get("name").asText().equals(node.get("name").asText())) {
					remove = count;
				}
			}
			if (remove >= 0) {
				plugins.remove(remove);
			}
			plugins.add(json);
			storeJSONConfig(config, getPluginsConfigPath());
		}
	}

	private void addPluginConfigurationAsPatch(JsonNode json) throws IOException {
		ArrayNode config = null;
		String configPath = getPluginsConfigPatchFilePath();
		Optional<File> pluginsConfigFile = findResource(configPath);

		if (pluginsConfigFile.isPresent()) {
			try (FileInputStream input = new FileInputStream(pluginsConfigFile.get())) {
				config = (ArrayNode) readJSON(input);
			} catch (FileNotFoundException e) {
				config = jsonNodeFactory.arrayNode();
			}
		} else {
			config = jsonNodeFactory.arrayNode();
		}
		if (config != null) {
			int remove = -1;
			for (int count = 0; count < config.size(); count++) {
				JsonNode node = config.get(count);
				if (json.get("name").asText().equals(node.get("value").get("name").asText())) {
					remove = count;
				}
			}
			if (remove >= 0) {
				config.remove(remove);
			}

			ObjectNode plugin = new ObjectNode(jsonNodeFactory);
			plugin.put("op", "add");
			plugin.put("path", "/plugins/-");
			plugin.set("value", json);
			config.add(plugin);
			storeJSONConfig(config, configPath);
		}
	}

	private ObjectNode getPluginsConfiguration() throws IOException {
		Optional<File> pluginsConfigFile = findResource(getPluginsConfigPath());
		if (pluginsConfigFile.isPresent()) {
			try (FileInputStream input = new FileInputStream(pluginsConfigFile.get())) {
				return (ObjectNode) readJSON(input);
			}
		} else {
			throw new FileNotFoundException(getPluginsConfigPath());
		}
	}

	private ArrayNode getPluginsConfigurationPatch() throws IOException {
		Optional<File> pluginsConfigFile = findResource(getPluginsConfigPatchFilePath());
		if (pluginsConfigFile.isPresent()) {
			try (FileInputStream input = new FileInputStream(pluginsConfigFile.get())) {
				return (ArrayNode) readJSON(input);
			}
		} else {
			throw new FileNotFoundException(getPluginsConfigPatchFilePath());
		}
	}

	private void storeJSONConfig(Object config, String configName) throws FileNotFoundException, IOException {
		ResourceUtils.storeJSONConfig(getWriteStorage(), context, config, configName);
	}

	private void addExtension(String pluginName, String pluginBundle, String translations)
			throws FileNotFoundException, IOException {
		ObjectNode config = null;
		Optional<File> extensionsConfigFile = findResource(getExtensionsConfigPath());
		if (extensionsConfigFile.isPresent()) {
			try (FileInputStream input = new FileInputStream(extensionsConfigFile.get())) {
				config = (ObjectNode) readJSON(input);
			} catch (FileNotFoundException e) {
				config = jsonNodeFactory.objectNode();
			}
		} else {
			config = jsonNodeFactory.objectNode();
		}
		if (config != null) {
			ObjectNode extension = jsonNodeFactory.objectNode();
			extension.put("bundle", pluginBundle);
			if (translations != null) {
				extension.put("translations", translations);
			}
			if (config.has(pluginName)) {
				config.replace(pluginName, extension);
			} else {
				config.set(pluginName, extension);
			}
			storeJSONConfig(config, getExtensionsConfigPath());
		}
	}

	private ObjectNode getExtensionConfig() throws IOException {
		Optional<File> extensionsConfigFile = findResource(getExtensionsConfigPath());
		if (extensionsConfigFile.isPresent()) {
			try (FileInputStream input = new FileInputStream(extensionsConfigFile.get())) {
				return (ObjectNode) readJSON(input);
			}
		} else {
			throw new FileNotFoundException();
		}
	}

	private JsonNode readJSON(InputStream input) throws IOException {
		byte[] buffer = new byte[1024];
		int read = 0;
		StringBuilder json = new StringBuilder();
		while ((read = input.read(buffer, 0, 1024)) >= 0) {
			json.append(new String(buffer, 0, read));
		}
		return jsonMapper.readTree(json.toString());
	}

	private void storeAsset(ZipInputStream zip, File file) throws FileNotFoundException, IOException {
		try (FileOutputStream outFile = new FileOutputStream(file)) {
			byte[] buffer = new byte[1024];
			int read = 0;
			while ((read = zip.read(buffer, 0, 1024)) >= 0) {
				outFile.write(buffer, 0, read);
			}
		}
	}

	public void setContext(ServletContext context) {
		this.context = context;
	}

	private String getExtensionsConfigPath() {
		return Paths.get(getExtensionsFolder(), getExtensionsConfig()).toString();
	}

	private String getPluginsConfigPath() {
		return Paths.get(getConfigsFolder(), getPluginsConfig()).toString();
	}

	private String getPluginsConfigPatchFilePath() {
		return getPluginsConfigPath() + ".patch";
	}

}
