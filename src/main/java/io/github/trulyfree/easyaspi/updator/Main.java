/*
 * EasyAsPi: A phone-based interface for the Raspberry Pi.
 * Copyright (C) 2017  vtcakavsmoace
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 * Raspberry Pi is a trademark of the Raspberry Pi Foundation.
 */

package io.github.trulyfree.easyaspi.updator;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.common.util.concurrent.FutureCallback;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.util.concurrent.ExecutorService;

import javax.annotation.Nullable;

import io.github.trulyfree.easyaspi.lib.disp.EAPDisplay;
import io.github.trulyfree.easyaspi.lib.disp.EAPDisplayableModule;
import io.github.trulyfree.easyaspi.lib.module.conf.ModuleConfig;
import io.github.trulyfree.easyaspi.lib.util.Utils;
import io.github.trulyfree.easyaspi.ssh.ssh.ChannelWrapper;
import io.github.trulyfree.easyaspi.ssh.ssh.Profile;
import io.github.trulyfree.easyaspi.ssh.ssh.SSHHandler;

@SuppressLint("SetTextI18n")
public class Main implements EAPDisplayableModule, PreferenceManager.OnActivityResultListener {

    private static final String UPDATE_COMMAND = "(sudo apt update; sudo apt -y dist-upgrade) 2>&1";

    private EAPDisplay activity;
    private SSHHandler sshHandler;
    private ExecutorService executorService;

    private Button update;
    private TextView logView;
    private ScrollView scrollView;

    public static ModuleConfig getConfig() {
        ModuleConfig config = new ModuleConfig();
        config.setTargetModule(Main.class.getName());
        config.setName("EasyAsPi Updater");
        return config;
    }

    @Override
    public boolean setup() {
        sshHandler = new SSHHandler(this);
        sshHandler.setup();
        return true;
    }

    @Override
    public boolean isReady() {
        return activity != null & sshHandler != null;
    }

    @Override
    public boolean destroy() {
        activity = null;
        sshHandler = null;
        return false;
    }

    @Override
    public EAPDisplay getActivity() {
        return activity;
    }

    @Override
    public void setActivity(EAPDisplay activity) {
        this.activity = activity;
    }

    @Override
    public ExecutorService getExecutorService() {
        return executorService;
    }

    @Override
    public void setExecutorService(ExecutorService executorService) {
        this.executorService = executorService;
    }

    @Override
    public View getRootView(Intent data) {
        RelativeLayout root = new RelativeLayout(activity);
        root.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        scrollView = new ScrollView(activity);
        scrollView.setLayoutParams(new RelativeLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
        ));
        logView = new TextView(activity);
        logView.setLayoutParams(new RelativeLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));
        logView.setVisibility(View.INVISIBLE);
        scrollView.addView(logView);
        root.addView(scrollView);
        update = new Button(activity);
        update.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                1f
        ));
        update.setText("Update and Upgrade");
        update.setOnClickListener(Utils.generateOnClickListener(new FutureCallback<View>() {
            @Override
            public void onSuccess(@Nullable View view) {
                ModuleConfig sshConfig = null;
                for (ModuleConfig config : activity.getModuleHandler().getConfigs()) {
                    if (config.getName().equals("EasyAsPi SSH")) {
                        sshConfig = config;
                        break;
                    }
                }
                if (sshConfig == null) {
                    activity.displayToUser("Please get the EasyAsPi SSH module.", Toast.LENGTH_LONG);
                }
                Intent myIntent = new Intent(activity, EAPDisplay.class);
                myIntent.putExtra("targetModule", activity.getModuleHandler().toJson(sshConfig));
                myIntent.putExtra("profileSelect", true);
                activity.startActivityForResult(myIntent, 0);
            }

            @Override
            public void onFailure(@NonNull Throwable throwable) {
                throwable.printStackTrace();
            }
        }));
        root.addView(update);
        return root;
    }

    @Override
    public ViewGroup.LayoutParams getLayoutParams() {
        return null;
    }

    @Override
    public boolean onActivityResult(int requestCode, int resultCode, Intent intent) {
        if (resultCode == Activity.RESULT_OK) {
            Profile profile = activity.getModuleHandler().getGson().fromJson(intent.getStringExtra("selectedProfile"), Profile.class);

            FutureCallback<ChannelWrapper> result = new FutureCallback<ChannelWrapper>() {
                private void setupLog() {
                    activity.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            update.setVisibility(View.INVISIBLE);
                            logView.setVisibility(View.VISIBLE);
                        }
                    });
                }

                private void revert() {
                    activity.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            update.setVisibility(View.VISIBLE);
                            logView.setVisibility(View.INVISIBLE);
                        }
                    });
                }

                @Override
                public void onSuccess(ChannelWrapper channelWrapper) {
                    try {
                        setupLog();
                        BufferedReader reader = new BufferedReader(new InputStreamReader(channelWrapper.getInputStream()));
                        for (String line;;) {
                            while ((line = reader.readLine()) != null) {
                                final String intermediary = line;
                                activity.runOnUiThread(new Runnable() {
                                    @Override
                                    public void run() {
                                        logView.append(intermediary);
                                        logView.append("\n");
                                        scrollView.scrollTo(0, scrollView.getBottom());
                                    }
                                });
                            }
                            if (channelWrapper.channelIsClosed()){
                                break;
                            }
                            try {
                                Thread.sleep(100);
                                System.out.println("Delayed.");
                            } catch (Exception e){
                                e.printStackTrace();
                            }
                        }
                        activity.runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                logView.append("Done!");
                                logView.setOnClickListener(new View.OnClickListener() {
                                    @Override
                                    public void onClick(View view) {
                                        revert();
                                    }
                                });
                            }
                        });
                    } catch (Throwable throwable) {
                        onFailure(throwable);
                    }
                }

                @Override
                public void onFailure(@NonNull Throwable throwable) {
                    activity.displayToUser(throwable.getMessage() + ".", Toast.LENGTH_SHORT);
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    PrintStream stream = new PrintStream(baos);
                    throwable.printStackTrace(stream);
                    try {
                        setupLog();
                        final String returned = baos.toString("UTF-8");
                        activity.runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                logView.append(returned);
                                logView.setOnClickListener(new View.OnClickListener() {
                                    @Override
                                    public void onClick(View view) {
                                        revert();
                                    }
                                });
                            }
                        });
                    } catch (Throwable secondary) {
                        secondary.printStackTrace();
                        activity.runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                revert();
                            }
                        });
                    }
                }
            };
            try {
                sshHandler.executeCommand(result, UPDATE_COMMAND, profile);
            } catch (Throwable throwable) {
                result.onFailure(throwable);
            }
        }
        return false;
    }
}
