package com.coldchain.compliance.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import javax.annotation.PostConstruct;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * 数字签名与哈希链工具。
 * <p>
 * 不可篡改保证：每条记录的 SHA-256(payload+prev_hash) 串联形成哈希链，再用 RSA-2048 签名。
 * DB 触发器禁止 UPDATE/DELETE 形成 WORM，组合实现"物理级"不可篡改。
 */
@Slf4j
@Component
public class SignatureUtil {

    @Value("${coldchain.signature.key-dir:/data/keys}")
    private String keyDir;

    private PrivateKey privateKey;
    private PublicKey publicKey;

    @PostConstruct
    public void init() {
        try {
            Files.createDirectories(Paths.get(keyDir));
            Path priv = Paths.get(keyDir, "system_private.pem");
            Path pub = Paths.get(keyDir, "system_public.pem");
            if (!Files.exists(priv)) {
                KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
                kpg.initialize(2048);
                KeyPair kp = kpg.generateKeyPair();
                writePem(priv, "PRIVATE KEY", kp.getPrivate().getEncoded());
                writePem(pub, "PUBLIC KEY", kp.getPublic().getEncoded());
                log.info("【签名-初始化】生成系统 RSA-2048 密钥对：{}", keyDir);
            }
            privateKey = readPriv(Files.readAllBytes(priv));
            publicKey = readPub(Files.readAllBytes(pub));
            log.info("【签名-初始化】密钥加载完成");
        } catch (Exception e) {
            throw new RuntimeException("签名系统初始化失败", e);
        }
    }

    private void writePem(Path p, String type, byte[] data) throws IOException {
        String b64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(data);
        try (BufferedWriter w = Files.newBufferedWriter(p, StandardCharsets.UTF_8)) {
            w.write("-----BEGIN " + type + "-----\n");
            w.write(b64);
            w.write("\n-----END " + type + "-----\n");
        }
    }

    private PrivateKey readPriv(byte[] pem) throws Exception {
        String s = new String(pem, StandardCharsets.UTF_8)
                .replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(s)));
    }

    private PublicKey readPub(byte[] pem) throws Exception {
        String s = new String(pem, StandardCharsets.UTF_8)
                .replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
        return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(s)));
    }

    /** SHA-256 哈希（16 进制） */
    public String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(data);
            StringBuilder sb = new StringBuilder(64);
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    public String sha256Hex(String s) {
        return sha256Hex(s.getBytes(StandardCharsets.UTF_8));
    }

    /** 计算当前点 payload 哈希：sha256( payload || prev_hash ) */
    public String chainHash(String payload, String prevHash) {
        String concat = payload == null ? "" : payload;
        if (prevHash != null) concat = concat + "|" + prevHash;
        return sha256Hex(concat);
    }

    /** RSA 签名（Base64） */
    public String sign(String content) {
        try {
            Signature s = Signature.getInstance("SHA256withRSA");
            s.initSign(privateKey);
            s.update(content.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(s.sign());
        } catch (Exception e) {
            throw new RuntimeException("签名失败", e);
        }
    }

    /** 验证签名（用于审计回放/数据校验） */
    public boolean verify(String content, String signature) {
        try {
            Signature s = Signature.getInstance("SHA256withRSA");
            s.initVerify(publicKey);
            s.update(content.getBytes(StandardCharsets.UTF_8));
            return s.verify(Base64.getDecoder().decode(signature));
        } catch (Exception e) {
            return false;
        }
    }

    public PublicKey getPublicKey() {
        return publicKey;
    }
}
