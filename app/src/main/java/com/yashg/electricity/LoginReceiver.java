package com.yashg.electricity;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Base64;

import java.nio.charset.StandardCharsets;

/**
 * Saves or clears the MGVCL login. Sent from tools/set-login.ps1 over ADB:
 *   am broadcast -n com.yashg.electricity/.LoginReceiver --es u64 <base64 user> --es p64 <base64 password>
 *   am broadcast -n com.yashg.electricity/.LoginReceiver --ez clear true
 * Base64 keeps quotes and spaces in a password away from the shell.
 */
public class LoginReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            if (intent.getBooleanExtra("clear", false)) {
                Creds.clear(context);
                setResultData("cleared");
                return;
            }
            String u64 = intent.getStringExtra("u64"), p64 = intent.getStringExtra("p64");
            if (u64 == null || p64 == null) {
                setResultData("missing u64/p64");
                return;
            }
            String user = new String(Base64.decode(u64, Base64.DEFAULT), StandardCharsets.UTF_8).trim();
            String pass = new String(Base64.decode(p64, Base64.DEFAULT), StandardCharsets.UTF_8);
            if (user.isEmpty() || pass.isEmpty()) {
                setResultData("empty user or password");
                return;
            }
            Creds.save(context, user, pass);
            setResultData("saved");
        } catch (Exception e) {
            setResultData("error: " + e.getClass().getSimpleName());
        }
    }
}
