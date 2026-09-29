package Color.fc;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.TranslateAnimation;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

/**
 * 访问密码锁：仅首次打开需要输入，验证一次后记住解锁状态
 */
public class LockActivity extends ThemedActivity {

    /** 密码盐值：参与摘要计算。代码中不存在明文密码——即使脱壳 dump
     *  内存也只能拿到摘要，无法逆推原密码 */
    private static final String PWD_SALT = "18dbe989d96ff5eb";

    /** 盐化摘要 SHA-256(PWD_SALT + 访问密码)，验证时对输入同样加盐求摘要比对 */
    private static final String PWD_DIGEST =
            "ce0c7af1fd7cc4fc99d08a73bcbc41115ca02a6a605fa74a06e8403d2244620d";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 已解锁过则直接进入主页
        SharedPreferences sp = getSharedPreferences("lock", MODE_PRIVATE);
        if (sp.getBoolean("unlocked", false)) {
            startActivity(new Intent(this, MainActivity.class));
            finish();
            return;
        }

        setContentView(R.layout.activity_lock);

        EditText input = findViewById(R.id.passwordInput);
        Button unlock = findViewById(R.id.unlockBtn);

        Runnable tryUnlock = () -> {
            String pwd = input.getText().toString();
            if (PWD_DIGEST.equals(sha256Hex(PWD_SALT + pwd))) {
                sp.edit().putBoolean("unlocked", true).apply();
                startActivity(new Intent(this, MainActivity.class));
                finish();
            } else {
                Toast.makeText(this, "密码错误", Toast.LENGTH_SHORT).show();
                shake(input);
                input.setText("");
            }
        };

        unlock.setOnClickListener(v -> tryUnlock.run());
        input.setOnEditorActionListener((v, actionId, event) -> {
            tryUnlock.run();
            return true;
        });
    }

    private void shake(View v) {
        Animation anim = new TranslateAnimation(-18, 18, 0, 0);
        anim.setDuration(50);
        anim.setRepeatCount(4);
        anim.setRepeatMode(Animation.REVERSE);
        v.startAnimation(anim);
    }

    private static String sha256Hex(String s) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            StringBuilder hex = new StringBuilder();
            for (byte b : md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Throwable t) {
            return "";
        }
    }
}
