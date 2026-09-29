package Color.fc.stub;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-GCM 密钥与封解实现。
 * 本文件被两处共用编译：APP 壳（StubApp 运行时解密）与主机封壳工具
 * （PackTool 加密），单一源码保证密钥与格式严格一致。
 *
 * 密钥不以明文存在：以两段随机掩码 M1/M2 异或存储，运行时组装，
 * 静态反编译看到的是两组无规律字节，需逆向组装逻辑才能还原。
 * AAD 绑定包名 Color.fc——密文移植到其他包名的壳中无法解密。
 */
final class KeyBox {

    // 掩码段 1（32 字节）
    private static final int[] M1 = {
            0xe9, 0x9c, 0x83, 0x64, 0x46, 0x60, 0xf4, 0x10,
            0x90, 0xf8, 0x62, 0xde, 0x6c, 0x89, 0xac, 0x28,
            0x39, 0xd0, 0x65, 0x45, 0xd6, 0x43, 0x7d, 0x16,
            0xa6, 0x75, 0x61, 0x29, 0xc9, 0xfc, 0x13, 0x11
    };

    // 掩码段 2（32 字节），真实密钥 = M1 xor M2
    private static final int[] M2 = {
            0x32, 0x9b, 0xdd, 0x1b, 0xbe, 0x07, 0x69, 0xd5,
            0xdd, 0x14, 0x6c, 0x69, 0xee, 0x11, 0x77, 0x42,
            0xbb, 0x9d, 0x78, 0x2b, 0x4d, 0xe0, 0xb0, 0x1e,
            0x55, 0xdf, 0x14, 0x56, 0x3c, 0x98, 0x79, 0x92
    };

    // 附加认证数据：绑定包名，跨包移植解密即失败
    private static final byte[] AAD = {0x43, 0x6f, 0x6c, 0x6f, 0x72, 0x2e, 0x66, 0x63}; // "Color.fc"

    private KeyBox() {
    }

    static byte[] key() {
        byte[] k = new byte[M1.length];
        for (int i = 0; i < k.length; i++) k[i] = (byte) (M1[i] ^ M2[i]);
        return k;
    }

    /** 加密：返回值已含 GCM 认证标签，篡改/错密钥解密必抛异常 */
    static byte[] seal(byte[] iv, byte[] plain) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key(), "AES"),
                new GCMParameterSpec(128, iv));
        c.updateAAD(AAD);
        return c.doFinal(plain);
    }

    static byte[] unseal(byte[] iv, byte[] data) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key(), "AES"),
                new GCMParameterSpec(128, iv));
        c.updateAAD(AAD);
        return c.doFinal(data);
    }
}
