package com.cnslab.pqc.common.crypto;

import org.bouncycastle.jcajce.SecretKeyWithEncapsulation;
import org.bouncycastle.jcajce.spec.KEMExtractSpec;
import org.bouncycastle.jcajce.spec.KEMGenerateSpec;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.pqc.jcajce.provider.BouncyCastlePQCProvider;
import org.bouncycastle.pqc.jcajce.spec.DilithiumParameterSpec;
import org.bouncycastle.pqc.jcajce.spec.KyberParameterSpec;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

public class SecurityUtils {

    static {
        // Register Bouncy Castle and BC PQC Providers
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
        if (Security.getProvider("BCPQC") == null) {
            Security.addProvider(new BouncyCastlePQCProvider());
        }
    }

    public record KyberEncapsulationResult(byte[] sharedSecret, byte[] encapsulationCiphertext) {}

    // ==========================================
    // CRYSTALS-Kyber (ML-KEM) Methods
    // ==========================================

    public static KeyPair generateKyberKeyPair() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("Kyber", "BCPQC");
        kpg.initialize(KyberParameterSpec.kyber768, new SecureRandom());
        return kpg.generateKeyPair();
    }

    public static PublicKey getKyberPublicKeyFromBytes(byte[] keyBytes) throws Exception {
        X509EncodedKeySpec spec = new X509EncodedKeySpec(keyBytes);
        KeyFactory kf = KeyFactory.getInstance("Kyber", "BCPQC");
        return kf.generatePublic(spec);
    }

    public static PrivateKey getKyberPrivateKeyFromBytes(byte[] keyBytes) throws Exception {
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(keyBytes);
        KeyFactory kf = KeyFactory.getInstance("Kyber", "BCPQC");
        return kf.generatePrivate(spec);
    }

    public static KyberEncapsulationResult encapsulate(PublicKey kyberPublicKey) throws Exception {
        KeyGenerator keyGen = KeyGenerator.getInstance("Kyber", "BCPQC");
        keyGen.init(new KEMGenerateSpec(kyberPublicKey, "AES"), new SecureRandom());
        SecretKeyWithEncapsulation senderSecret = (SecretKeyWithEncapsulation) keyGen.generateKey();
        return new KyberEncapsulationResult(senderSecret.getEncoded(), senderSecret.getEncapsulation());
    }

    public static byte[] decapsulate(PrivateKey kyberPrivateKey, byte[] encapsulation) throws Exception {
        KeyGenerator keyGen = KeyGenerator.getInstance("Kyber", "BCPQC");
        keyGen.init(new KEMExtractSpec(kyberPrivateKey, encapsulation, "AES"));
        SecretKeyWithEncapsulation receiverSecret = (SecretKeyWithEncapsulation) keyGen.generateKey();
        return receiverSecret.getEncoded();
    }

    // ==========================================
    // CRYSTALS-Dilithium (ML-DSA) Methods
    // ==========================================

    public static KeyPair generateDilithiumKeyPair() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("Dilithium", "BCPQC");
        kpg.initialize(DilithiumParameterSpec.dilithium3, new SecureRandom());
        return kpg.generateKeyPair();
    }

    public static PublicKey getDilithiumPublicKeyFromBytes(byte[] keyBytes) throws Exception {
        X509EncodedKeySpec spec = new X509EncodedKeySpec(keyBytes);
        KeyFactory kf = KeyFactory.getInstance("Dilithium", "BCPQC");
        return kf.generatePublic(spec);
    }

    public static PrivateKey getDilithiumPrivateKeyFromBytes(byte[] keyBytes) throws Exception {
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(keyBytes);
        KeyFactory kf = KeyFactory.getInstance("Dilithium", "BCPQC");
        return kf.generatePrivate(spec);
    }

    public static byte[] signDilithium(PrivateKey privateKey, byte[] data) throws Exception {
        Signature sig = Signature.getInstance("Dilithium", "BCPQC");
        sig.initSign(privateKey);
        sig.update(data);
        return sig.sign();
    }

    public static boolean verifyDilithium(PublicKey publicKey, byte[] data, byte[] signature) throws Exception {
        Signature sig = Signature.getInstance("Dilithium", "BCPQC");
        sig.initVerify(publicKey);
        sig.update(data);
        return sig.verify(signature);
    }

    // ==========================================
    // AES-256-GCM Encryption / Decryption Methods
    // ==========================================

    public static byte[] encryptAES_GCM(byte[] data, byte[] key) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        byte[] iv = new byte[12];
        new SecureRandom().nextBytes(iv);
        GCMParameterSpec spec = new GCMParameterSpec(128, iv);
        SecretKeySpec keySpec = new SecretKeySpec(key, "AES");
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, spec);
        byte[] ciphertext = cipher.doFinal(data);

        // Combine IV (12 bytes) and ciphertext
        byte[] combined = new byte[iv.length + ciphertext.length];
        System.arraycopy(iv, 0, combined, 0, iv.length);
        System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);
        return combined;
    }

    public static byte[] decryptAES_GCM(byte[] combined, byte[] key) throws Exception {
        if (combined.length < 12) {
            throw new IllegalArgumentException("Invalid AES GCM encrypted data size");
        }
        byte[] iv = new byte[12];
        System.arraycopy(combined, 0, iv, 0, 12);

        byte[] ciphertext = new byte[combined.length - 12];
        System.arraycopy(combined, 12, ciphertext, 0, ciphertext.length);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        GCMParameterSpec spec = new GCMParameterSpec(128, iv);
        SecretKeySpec keySpec = new SecretKeySpec(key, "AES");
        cipher.init(Cipher.DECRYPT_MODE, keySpec, spec);
        return cipher.doFinal(ciphertext);
    }

    // ==========================================
    // Hashing helper
    // ==========================================

    public static byte[] calculateSHA256(byte[] data) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return digest.digest(data);
    }
}
