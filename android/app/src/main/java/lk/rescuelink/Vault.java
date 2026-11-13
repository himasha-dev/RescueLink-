package lk.rescuelink;

import android.content.*;
import android.security.keystore.*;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;

/** Encrypts sessions and outbox with a non-exportable Android Keystore key. */
final class Vault {
  private static SecretKey key() throws Exception {
    KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
    ks.load(null);
    if (!ks.containsAlias("RescueLinkVault")) {
      KeyGenerator g = KeyGenerator.getInstance("AES", "AndroidKeyStore");
      g.init(
          new KeyGenParameterSpec.Builder(
                  "RescueLinkVault", KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
              .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
              .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
              .build());
      g.generateKey();
    }
    return (SecretKey) ks.getKey("RescueLinkVault", null);
  }

  static synchronized void put(Context c, String name, String value) throws Exception {
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.ENCRYPT_MODE, key());
    String encoded =
        Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP)
            + ":"
            + Base64.encodeToString(
                cipher.doFinal(value.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
    if (!c.getSharedPreferences("vault", 0).edit().putString(name, encoded).commit())
      throw new java.io.IOException("Could not save locally");
  }

  static synchronized String get(Context c, String name) throws Exception {
    String stored = c.getSharedPreferences("vault", 0).getString(name, "");
    if (stored.isEmpty()) return "";
    String[] a = stored.split(":");
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(
        Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(a[0], Base64.NO_WRAP)));
    return new String(cipher.doFinal(Base64.decode(a[1], Base64.NO_WRAP)), StandardCharsets.UTF_8);
  }

  static synchronized void remove(Context c, String name) {
    c.getSharedPreferences("vault", 0).edit().remove(name).commit();
  }
}
