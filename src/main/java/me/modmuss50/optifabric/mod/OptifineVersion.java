package me.modmuss50.optifabric.mod;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import me.modmuss50.optifabric.util.ASMUtils;
import me.modmuss50.optifabric.util.ZipUtils;
import net.fabricmc.loader.api.FabricLoader;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.mutable.MutableBoolean;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.DefaultHttpClient;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;

import javax.swing.*;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.zip.ZipError;
import java.util.zip.ZipException;

public class OptifineVersion {
	public static String version;
	public static String minecraftVersion;
	public static JarType jarType;

	public static File findOptifineJar() throws IOException {
		@SuppressWarnings("deprecation")
		File modsDir = new File(FabricLoader.getInstance().getGameDirectory(), "mods");
		File[] mods = modsDir.listFiles();

		if (mods != null) {
			File optifineJar = null;
			for (File file : mods) {
				if (!file.isDirectory() && "jar".equals(FilenameUtils.getExtension(file.getName())) && !file.getName().startsWith(".") && !file.isHidden()) {
					JarType type = getJarType(file);
					if (type.isError()) {
						jarType = type;
						throw new RuntimeException("An error occurred when trying to find the optifine jar: " + type.name());
					}

					if (type == JarType.OPTIFINE_MOD || type == JarType.OPTIFINE_INSTALLER) {
						if (optifineJar != null) {
							jarType = JarType.DUPLICATED;
							OptifabricError.setError("Please ensure you only have 1 copy of OptiFine in the mods folder!\nFound: %s\n       %s", optifineJar, file);
							throw new FileAlreadyExistsException("Multiple optifine jars: " + file.getName() + " and " + optifineJar.getName());
						}

						jarType = type;
						optifineJar = file;
					}
				}
			}

			if(optifineJar == null){
				optifineJar = downloadAndPassJar(modsDir);
			}

			if (optifineJar != null) {
				return optifineJar;
			}
		}


		jarType = JarType.MISSING;
		OptifabricError.setError("OptiFabric could not find the Optifine jar in the mods folder:\n%s", modsDir);
		throw new FileNotFoundException("Could not find optifine jar");
	}

	private static File downloadAndPassJar(File modsDir){
		// OMG, Optifine JAR is missing! let's download one!


		String currentMcVersion = "unknown";
		try (JsonReader in = new JsonReader(new InputStreamReader(OptifineVersion.class.getResourceAsStream("/version.json")))) {
			in.beginObject();

			while (in.hasNext()) {
				if ("id".equals(in.nextName())) {
					currentMcVersion = in.nextString();
					break;
				} else {
					in.skipValue();
				}
			}
		} catch (IOException | IllegalStateException e) {
			OptifabricError.setError(e, "Failed to find current minecraft version, please report this");
			e.printStackTrace();
			return null;
		}

		JOptionPane.showMessageDialog(new JPanel(), "<html><body><p>由于 Optifine 的 EULA 协议，Bilicraft 不可以在 Modpack 中分发 Optifine 的二进制文件。</p>" +
				"<p>因此，我们即将从 BMCLAPI 下载 Optifine 并自动安装到您的游戏中。下载过程中，游戏可能不会显示任何窗口。" +
				"</body></html>", "Optifabric - Bilicraft Edition >> 需要下载依赖文件", JOptionPane.INFORMATION_MESSAGE);

		try {
			HttpClient httpClient = new DefaultHttpClient();
			HttpGet httpGet = new HttpGet("https://bmclapi2.bangbang93.com/optifine/:mcversion"
					.replace(":mcversion", currentMcVersion));
			List<OptifineApiReturns> returns = new Gson().fromJson(IOUtils.toString(httpClient.execute(httpGet).getEntity().getContent(), StandardCharsets.UTF_8), new TypeToken<ArrayList<OptifineApiReturns>>() {}.getType());
			OptifineApiReturns newestRelease = returns.get(0);
			OptifineApiReturns newestPre = returns.get(returns.size() - 1);
			OptifineApiReturns finalUse;
			if (newestRelease.patch.startsWith("pre"))
				finalUse = newestPre;
			else
				finalUse = newestRelease;
			File downloadJar = new File(modsDir, "Optifine_" + currentMcVersion+ "_" + finalUse.type + "_" + finalUse.patch + ".jar");
			JOptionPane.showMessageDialog(new JPanel(), "<html><body><p>即将下载 Optifine，请核对相关信息，如有误，您可以稍后手动安装</p>" +
					"<p>版本："+ finalUse.mcversion +"</p>"+
					"<p>类型："+ finalUse.type+"</p>" +
					"<p>Optifine版本："+finalUse.patch+"</p>" +
					"<p></p>" +
					"<p>将为您安装到："+downloadJar.getAbsolutePath()+"</p>" +
					"<p></p>" +
					"<p>下载一旦完成，游戏将会继续启动，请耐心等待。</p>"+
					"</body></html>", "Optifabric - Bilicraft Edition >> 下载确认", JOptionPane.INFORMATION_MESSAGE);

			httpGet = new HttpGet("https://bmclapi2.bangbang93.com/optifine/:mcversion/:type/:patch"
					.replace(":mcversion", currentMcVersion)
					.replace(":type", finalUse.type)
					.replace(":patch", finalUse.patch));
			HttpResponse response = httpClient.execute(httpGet);
			Files.copy(response.getEntity().getContent(),downloadJar.toPath(), StandardCopyOption.REPLACE_EXISTING);
			JarType type = getJarType(downloadJar);
			if (type.isError()) {
				jarType = JarType.CORRUPT_ZIP;
				throw new RuntimeException("An error occurred when trying to find the optifine jar: " + type.name());
			}

			if (type == JarType.OPTIFINE_MOD || type == JarType.OPTIFINE_INSTALLER) {
				jarType = type;
				return downloadJar;
			}
			return null;
		}catch (Exception exception){
			exception.printStackTrace();
			System.out.println("[OptiFabric - Bilicraft Edition] Failed to download Optifine jar.");
			JOptionPane.showMessageDialog(new JPanel(), "<html><body><p>下载过程中出现错误："+exception.getMessage()+"</p>" +
					"<p>请手动下载 Optifine "+ currentMcVersion+" 的二进制文件放入 mods 文件夹中" +
					"</body></html>", "Optifabric - Bilicraft Edition >> 依赖文件下载失败", JOptionPane.ERROR_MESSAGE);
			return null;
		}
	}

	private static JarType getJarType(File file) throws IOException {
		ClassNode classNode;
		try (JarFile jarFile = new JarFile(file)) {
			JarEntry jarEntry = jarFile.getJarEntry("net/optifine/Config.class"); // New 1.14.3 location
			if (jarEntry == null) {
				return JarType.SOMETHING_ELSE;
			}
			classNode = ASMUtils.readClass(jarFile, jarEntry);
		} catch (ZipException | ZipError e) {
			OptifabricError.setError("The jar at " + file + " is corrupt");
			return JarType.CORRUPT_ZIP;
		}

		for (FieldNode fieldNode : classNode.fields) {
			if ("VERSION".equals(fieldNode.name)) {
				version = (String) fieldNode.value;
			}
			if ("MC_VERSION".equals(fieldNode.name)) {
				minecraftVersion = (String) fieldNode.value;
			}
		}

		if (version == null || version.isEmpty() || minecraftVersion == null || minecraftVersion.isEmpty()) {
			OptifabricError.setError("Unable to find OptiFine version from OptiFine jar at " + file);
			return JarType.INCOMPATIBLE;
		}

		String currentMcVersion = "unknown";
		try (JsonReader in = new JsonReader(new InputStreamReader(OptifineVersion.class.getResourceAsStream("/version.json")))) {
			in.beginObject();

			while (in.hasNext()) {
				if ("id".equals(in.nextName())) {
					currentMcVersion = in.nextString();
					break;
				} else {
					in.skipValue();
				}
			}
		} catch (IOException | IllegalStateException e) {
			OptifabricError.setError(e, "Failed to find current minecraft version, please report this");
			e.printStackTrace();
			return JarType.INTERNAL_ERROR;
		}

		if (!currentMcVersion.equals(minecraftVersion)) {
			OptifabricError.setError("This version of OptiFine from %s is not compatible with the current minecraft version\n\nOptifine requires %s you are running %s",
					file, minecraftVersion, currentMcVersion);
			return JarType.INCOMPATIBLE;
		}

		MutableBoolean isInstaller = new MutableBoolean(false);
		ZipUtils.iterateContents(file, (zip, zipEntry) -> {
			if (zipEntry.getName().startsWith("patch/")) {
				isInstaller.setTrue();
				return false;
			} else {
				return true;
			}
		});

		if (isInstaller.isTrue()) {
			return JarType.OPTIFINE_INSTALLER;
		} else {
			return JarType.OPTIFINE_MOD;
		}
	}

	public enum JarType {
		MISSING(true),
		OPTIFINE_MOD(false),
		OPTIFINE_INSTALLER(false),
		INCOMPATIBLE(true),
		CORRUPT_ZIP(true),
		DUPLICATED(true),
		INTERNAL_ERROR(true),
		SOMETHING_ELSE(false);

		private final boolean error;

		JarType(boolean error) {
			this.error = error;
		}

		public boolean isError() {
			return error;
		}
	}

	public static class OptifineApiReturns{
		private String _id;
		private String mcversion;
		private String patch;
		private String type;
		private Integer __v;
		private String filename;
	}
}
