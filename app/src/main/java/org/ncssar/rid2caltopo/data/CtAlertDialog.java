package org.ncssar.rid2caltopo.data;

import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import org.ncssar.rid2caltopo.app.R2CActivity;
import org.ncssar.rid2caltopo.data.CaltopoClient;

public class CtAlertDialog {
    private static final String TAG = "CtAlertDialog";
    private AlertDialog.Builder builder;
    private boolean response;
    private boolean responseValid;
    public CtAlertDialog(@NonNull String title, @NonNull String question, @Nullable Runnable responseRunnable) {
        builder = new AlertDialog.Builder(R2CActivity.getR2CActivity());
        builder.setTitle(title);
        builder.setMessage(question);
        builder.setPositiveButton("Yes", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                responseValid = response = true;
                CaltopoClient.CTDebug(TAG, "User responded affirmative to question: " + question);
                if (null != responseRunnable) responseRunnable.run();
            }
        });

        // Negative button
        builder.setNegativeButton("No", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                // User clicked "No", dismiss the dialog
                CaltopoClient.CTDebug(TAG, "User responded 'no' to question: " + question);
                response = false;
                responseValid = true;
                if (null != responseRunnable) responseRunnable.run();
//              dialog.dismiss();
            }
        });

        // Create and show the AlertDialog
        AlertDialog alertDialog = builder.create();
        alertDialog.show();
    }
    public boolean isResponseValid() {return responseValid;}
    public boolean getResponse() {return response;}
}