package Color.fc.stub;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * 主机端封壳工具（爱加密式 DEX 加壳的加密侧）。
 * 与 APP 壳共用同一份 KeyBox 源码，密钥/格式天然一致。
 *
 * 用法：
 *   seal   <real.dex> <cfc.dat>   加密真实 dex 生成资产密文
 *   unseal <cfc.dat>  <out.dex>   解密回读（构建时自验证 roundtrip）
 */
public class PackTool {

    public static void main(String[] args) throws Exception {
        String cmd = args[0];
        if ("seal".equals(cmd)) {
            byte[] dex = Files.readAllBytes(Paths.get(args[1]));
            byte[] iv = new byte[12];
            SecureRandom.getInstanceStrong().nextBytes(iv);
            byte[] ct = KeyBox.seal(iv, dex);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write('C');
            out.write('F');
            out.write('C');
            out.write('1');
            out.write(iv);
            out.write(ct);
            Files.write(Paths.get(args[2]), out.toByteArray());
            System.out.println("sealed: " + dex.length + " -> " + out.size() + " bytes");
        } else if ("unseal".equals(cmd)) {
            byte[] all = Files.readAllBytes(Paths.get(args[1]));
            if (all.length < 4 + 12 + 16
                    || all[0] != 'C' || all[1] != 'F' || all[2] != 'C' || all[3] != '1') {
                throw new IllegalStateException("bad magic");
            }
            byte[] iv = Arrays.copyOfRange(all, 4, 16);
            byte[] ct = Arrays.copyOfRange(all, 16, all.length);
            Files.write(Paths.get(args[2]), KeyBox.unseal(iv, ct));
            System.out.println("unsealed ok");
        } else {
            throw new IllegalArgumentException("unknown: " + cmd);
        }
    }
}
