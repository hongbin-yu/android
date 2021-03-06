package wang.switchy.hin2n.activity;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.design.widget.FloatingActionButton;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.View;
import android.widget.Toast;


import androidx.annotation.NonNull;

import com.budiyev.android.codescanner.CodeScanner;
import com.budiyev.android.codescanner.CodeScannerView;
import com.budiyev.android.codescanner.DecodeCallback;
import com.google.zxing.Result;


import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;

import cn.pedant.SweetAlert.SweetAlertDialog;
import wang.switchy.hin2n.Application;
import wang.switchy.hin2n.R;
import wang.switchy.hin2n.model.EdgeCmd;
import wang.switchy.hin2n.storage.db.base.N2NSettingModelDao;
import wang.switchy.hin2n.storage.db.base.model.N2NSettingModel;

public class ScanQRActivity extends AppCompatActivity {

    private N2NSettingModel mN2NSettingModel;
    private CodeScanner mCodeScanner;
    private SharedPreferences mHin2nSp;
    private SharedPreferences.Editor mHin2nEdit;
    // Debug log tag.
    private static final String TAG_HTTP_URL_CONNECTION = "HTTP_URL_CONNECTION";
    // Child thread sent message type value to activity main thread Handler.
    private static final int REQUEST_CODE_SHOW_RESPONSE_TEXT = 1;

    // The key of message stored server returned data.
    private static final String KEY_RESPONSE_TEXT = "KEY_RESPONSE_TEXT";
    private static final String KEY_RESPONSE_CONTENTTYPE = "KEY_RESPONSE_CONTENTTYPE";
    private static final String KEY_REQ_URL = "KEY_REQ_URL";

    // Request method GET. The value must be uppercase.
    private static final String REQUEST_METHOD_GET = "GET";
    // This handler used to listen to child thread show return page html text message and display those text in responseTextView.
    private Handler uiUpdater = null;
    private String device_id;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_scan_qr);
        setTitle(R.string.title_scan_qcode);
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        mHin2nSp = getSharedPreferences("Hin2n", MODE_PRIVATE);
        mHin2nEdit = mHin2nSp.edit();
        /*
        toolbar.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if(mCodeScanner != null)
                    mCodeScanner.releaseResources();
                finish();
            }
        });
        */
        initControls();
        FloatingActionButton fab = findViewById(R.id.fab);
        fab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Intent intent = new Intent(ScanQRActivity.this, SettingDetailsActivity.class);
                intent.putExtra("type", SettingDetailsActivity.TYPE_SETTING_ADD);
                startActivity(intent);
            }
        });
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        if (ContextCompat.checkSelfPermission(ScanQRActivity.this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_DENIED){
            ActivityCompat.requestPermissions(ScanQRActivity.this, new String[] {Manifest.permission.CAMERA}, 123);
        } else {
            startScanning();
        }


    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 123) {
            if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Camera permission granted", Toast.LENGTH_LONG).show();
                startScanning();
            } else {
                Toast.makeText(this, "Camera permission denied", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void startScanning() {
        CodeScannerView scannerView = findViewById(R.id.scanner_view);
        mCodeScanner = new CodeScanner(this, scannerView);
        mCodeScanner.setDecodeCallback(new DecodeCallback() {
            @Override
            public void onDecoded(@NonNull final Result result) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        device_id = result.getText();
                        if(device_id.indexOf("token=") > 0) {
                            startSendHttpRequestThread(getString(R.string.app_fetch_url)+device_id+"&download");
                        }
                        Toast.makeText(ScanQRActivity.this, result.getText(), Toast.LENGTH_LONG).show();
                    }
                });
            }
        });
        mCodeScanner.startPreview();

        scannerView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                mCodeScanner.startPreview();
            }
        });

    }

    @Override
    protected void onResume() {
        super.onResume();
        if(mCodeScanner != null) {
            mCodeScanner.startPreview();
        }
    }

    @Override
    protected void onPause() {
        if(mCodeScanner != null) {
            mCodeScanner.releaseResources();
        }
        super.onPause();
    }
    @Override
    public void onBackPressed() {
        if(mCodeScanner != null)
            mCodeScanner.releaseResources();
        super.onBackPressed();
    }
    // Initialize app controls.
    private void initControls()
    {
        // This handler is used to wait for child thread message to update server response text in TextView.
        if(uiUpdater == null)
        {
            uiUpdater = new Handler()
            {
                @Override
                public void handleMessage(Message msg) {
                    if(msg.what == REQUEST_CODE_SHOW_RESPONSE_TEXT)
                    {
                        Bundle bundle = msg.getData();
                        if(bundle != null)
                        {
                            String responseText = bundle.getString(KEY_RESPONSE_TEXT);
                            String contentType = bundle.getString(KEY_RESPONSE_CONTENTTYPE);
                            String reqUrl = bundle.getString(KEY_REQ_URL);

                            if (responseText != null) {
                                try {
                                    JSONObject job = new JSONObject(responseText);


                                    N2NSettingModelDao n2NSettingModelDao = Application.getInstance().getDaoSession().getN2NSettingModelDao();

                                    String settingName = job.getString("name");
                                    String setingNameTmp = settingName;//原始字符串
                                    int i = 0;
                                    while (n2NSettingModelDao.queryBuilder().where(N2NSettingModelDao.Properties.Name.eq(settingName)).unique() != null) {
                                        i++;
                                        settingName = setingNameTmp + "(" + i + ")";
                                    }
                                    String ip = job.getString("IPAddress");
                                    mN2NSettingModel = new N2NSettingModel(null, 1, settingName,
                                            //job.getString("IPAddress"), job.getString("mask"),
                                            ip,job.getString("mask"),
                                            job.getString("community"), job.getString("password"),
                                            job.getString("supernode"), true,
                                            job.getString("supernodeBackup"), EdgeCmd.getRandomMac(),
                                            job.getInt("mtu"),job.getString("localIP"),
                                            job.getInt("holePunchInterval"), job.getBoolean("resoveSupernodeIP"),
                                            job.getInt("localPort"), job.getBoolean("allowRoutin"),
                                            job.getBoolean("acceptMuticast"), job.getBoolean("useHttpTunnel"),
                                            job.getInt("traceLevel"), false,device_id,null,null);
                                    n2NSettingModelDao.insert(mN2NSettingModel);
                                    mN2NSettingModel = n2NSettingModelDao.queryBuilder().where(N2NSettingModelDao.Properties.IsSelcected.eq(true)).unique();
                                    mHin2nEdit.putLong("current_setting_id", mN2NSettingModel.getId());
                                    mHin2nEdit.commit();

                                    new SweetAlertDialog(ScanQRActivity.this, SweetAlertDialog.SUCCESS_TYPE)
                                            .setTitleText(getString(R.string.dialog_add_succeed))
                                            .setConfirmClickListener(new SweetAlertDialog.OnSweetClickListener() {
                                                @Override
                                                public void onClick(SweetAlertDialog sweetAlertDialog) {
                                                    finish();
                                                    sweetAlertDialog.dismiss();
                                                }
                                            }).show();

                                } catch (JSONException e) {
                                    new SweetAlertDialog(ScanQRActivity.this,SweetAlertDialog.ERROR_TYPE)
                                            .setTitleText("Error:"+reqUrl)
                                            .setContentText("Something went wrong!"+e.getMessage())
                                            .show();
                                    Log.e(TAG_HTTP_URL_CONNECTION,e.getMessage()+responseText);
                                }
                            }
                        }
                    }
                }
            };
        }
    }
    /* Start a thread to send http request to web server use HttpURLConnection object. */
    protected void startSendHttpRequestThread(final String reqUrl)
    {

        Thread sendHttpRequestThread = new Thread()
        {
            @Override
            public void run() {
                // Maintain http url connection.
                HttpURLConnection httpConn = null;

                // Read text input stream.
                InputStreamReader isReader = null;

                // Read text into buffer.
                BufferedReader bufReader = null;

                // Save server response text.
                StringBuffer readTextBuf = new StringBuffer();

                try {
                    // Create a URL object use page url.
                    URL url = new URL(reqUrl);

                    // Open http connection to web server.
                    httpConn = (HttpURLConnection)url.openConnection();

                    // Set http request method to get.
                    httpConn.setRequestMethod(REQUEST_METHOD_GET);

                    // Set connection timeout and read timeout value.
                    httpConn.setConnectTimeout(10000);
                    httpConn.setReadTimeout(30000);
                    int code = httpConn.getResponseCode();
                    String contentType = httpConn.getContentType();
                    // Get input stream from web url connection.
                    InputStream inputStream = httpConn.getInputStream();

                    // Create input stream reader based on url connection input stream.
                    isReader = new InputStreamReader(inputStream);

                    // Create buffered reader.
                    bufReader = new BufferedReader(isReader);

                    // Read line of text from server response.
                    String line = bufReader.readLine();

                    // Loop while return line is not null.
                    while(line != null)
                    {
                        // Append the text to string buffer.
                        readTextBuf.append(line);

                        // Continue to read text line.
                        line = bufReader.readLine();
                    }

                    // Send message to main thread to update response text in TextView after read all.
                    Message message = new Message();

                    // Set message type.
                    message.what = REQUEST_CODE_SHOW_RESPONSE_TEXT;

                    // Create a bundle object.
                    Bundle bundle = new Bundle();
                    // Put response text in the bundle with the special key.
                    bundle.putString(KEY_RESPONSE_TEXT, readTextBuf.toString());
                    bundle.putString(KEY_RESPONSE_CONTENTTYPE, contentType);
                    bundle.putString(KEY_REQ_URL, reqUrl);
                    // Set bundle data in message.
                    message.setData(bundle);
                    // Send message to main thread Handler to process.
                    uiUpdater.sendMessage(message);
                }catch(MalformedURLException ex)
                {
                    Log.e(TAG_HTTP_URL_CONNECTION, ex.getMessage(), ex);
                    new SweetAlertDialog(ScanQRActivity.this,SweetAlertDialog.ERROR_TYPE)
                            .setTitleText("Error:"+reqUrl)
                            .setContentText("Something went wrong!"+ex.toString())
                            .show();
                }catch(IOException ex) {
                    Log.e(TAG_HTTP_URL_CONNECTION, ex.getMessage(), ex);
                    new SweetAlertDialog(ScanQRActivity.this, SweetAlertDialog.ERROR_TYPE)
                            .setTitleText("Error:" + reqUrl)
                            .setContentText("Something went wrong! " + ex.toString())
                            .show();
                }finally {
                    try {
                        if (bufReader != null) {
                            bufReader.close();
                            bufReader = null;
                        }

                        if (isReader != null) {
                            isReader.close();
                            isReader = null;
                        }

                        if (httpConn != null) {
                            httpConn.disconnect();
                            httpConn = null;
                        }
                    }catch (IOException ex)
                    {
                        Log.e(TAG_HTTP_URL_CONNECTION, ex.getMessage(), ex);
                    }
                }
            }
        };
        // Start the child thread to request web page.
        sendHttpRequestThread.start();
    }

}
