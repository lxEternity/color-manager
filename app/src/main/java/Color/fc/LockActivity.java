package Color.fc;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.TranslateAnimation;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

/**
 * 访问密码锁
 */
public class LockActivity extends Activity {

    /** 访问密码 */
    static final String ACCESS_PASSWORD = "bjs5120";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_lock);

        EditText input = findViewById(R.id.passwordInput);
        Button unlock = findViewById(R.id.unlockBtn);

        Runnable tryUnlock = () -> {
            String pwd = input.getText().toString();
            if (ACCESS_PASSWORD.equals(pwd)) {
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
}
