package com.koko.cfjs;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.DialogInterface;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.text.InputType;
import android.util.Base64;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.fongmi.android.tv.App;
import com.github.catvod.Runtime;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Host for catvod {@code Runtime.dialog} / {@code Spider.dialog(JSONObject)} (upstream ui/dialog/SpiderDialog),
 * built from plain framework views. Same option keys: title, message, copyable, qrcode (text drawn as QR),
 * image (data:image/...;base64), inputs (text/textarea/phone/number/password/select/multiselect), buttons
 * (max 3: id, label, close, enabled), cancelable; button events are {"button":id,"values":{...}}, a callback
 * may answer a patch (qrcode/message/image/values/errors/close). Drive spiders use this for QR / cookie login.
 * The dialog needs the app in front (Runtime.activity); otherwise it fails and the log says so.
 */
public final class CfjsDialog {

    private static final int[] WHICH = {DialogInterface.BUTTON_POSITIVE, DialogInterface.BUTTON_NEGATIVE, DialogInterface.BUTTON_NEUTRAL};
    private final CompletableFuture<String> result = new CompletableFuture<>();
    private final Function<String, CompletableFuture<String>> callback;
    private final Map<String, Object> inputs = new LinkedHashMap<>();
    private final Map<Integer, JSONObject> buttons = new HashMap<>();
    private AlertDialog dialog;
    private TextView message;
    private ImageView qrcode;
    private ImageView image;
    private boolean busy;

    private CfjsDialog(Function<String, CompletableFuture<String>> callback) {
        this.callback = callback;
    }

    public static CompletableFuture<String> show(String options, Function<String, CompletableFuture<String>> callback) {
        CfjsDialog request = new CfjsDialog(callback);
        NetDisk.i("spider dialog: " + NetDisk.cut(summary(options), 200));
        App.post(() -> request.open(options));
        request.result.whenComplete((value, error) -> App.post(request::close));
        return request.result;
    }

    private static String summary(String options) {
        try {
            JSONObject json = new JSONObject(options);
            return "title=" + json.optString("title") + " message=" + json.optString("message") + " qrcode=" + json.has("qrcode") + " image=" + json.has("image") + " inputs=" + (json.has("inputs") ? json.getJSONArray("inputs").length() : 0);
        } catch (Exception e) {
            return options;
        }
    }

    private void open(String options) {
        try {
            Activity activity = Runtime.activity();
            if (activity == null) throw new IllegalStateException("没有前台界面：请打开 Spider 服务的「网盘登录」页再操作");
            JSONObject json = new JSONObject(options);
            LinearLayout content = new LinearLayout(activity);
            content.setOrientation(LinearLayout.VERTICAL);
            int pad = dp(activity, 20);
            content.setPadding(pad, dp(activity, 8), pad, dp(activity, 8));
            message = new TextView(activity);
            message.setTextIsSelectable(json.optBoolean("copyable", false));
            content.addView(message);
            setMessage(json.optString("message"));
            if (json.optBoolean("copyable", false)) {
                Button copy = new Button(activity);
                copy.setText("复制");
                copy.setOnClickListener(v -> {
                    activity.getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("spider", message.getText()));
                    Toast.makeText(activity, "已复制", Toast.LENGTH_SHORT).show();
                });
                content.addView(copy);
            }
            qrcode = addImage(activity, content);
            image = addImage(activity, content);
            if (json.has("qrcode")) setQRCode(json.optString("qrcode"));
            if (json.has("image")) setImage(json.optString("image"));
            JSONArray fields = json.optJSONArray("inputs");
            if (fields != null) for (int i = 0; i < fields.length(); i++) addInput(activity, content, fields.getJSONObject(i));
            ScrollView scroll = new ScrollView(activity);
            scroll.addView(content);
            AlertDialog.Builder builder = new AlertDialog.Builder(activity).setTitle(json.optString("title")).setView(scroll);
            JSONArray list = json.optJSONArray("buttons");
            if (list == null || list.length() == 0) {
                list = new JSONArray().put(new JSONObject().put("id", "ok").put("label", "确定")).put(new JSONObject().put("id", "cancel").put("label", "取消").put("cancel", true));
            }
            for (int i = 0; i < Math.min(3, list.length()); i++) {
                JSONObject button = list.getJSONObject(i);
                buttons.put(WHICH[i], button);
                String label = button.optString("label", button.optString("id"));
                if (i == 0) builder.setPositiveButton(label, null);
                else if (i == 1) builder.setNegativeButton(label, null);
                else builder.setNeutralButton(label, null);
            }
            dialog = builder.create();
            dialog.setCancelable(json.optBoolean("cancelable", true));
            dialog.setOnDismissListener(d -> result.complete(null));
            dialog.show();
            for (Map.Entry<Integer, JSONObject> entry : buttons.entrySet()) {
                Button view = dialog.getButton(entry.getKey());
                if (view == null) continue;
                view.setEnabled(entry.getValue().optBoolean("enabled", true));
                view.setOnClickListener(v -> onButton(entry.getValue()));
            }
        } catch (Throwable e) {
            NetDisk.w("spider dialog failed: " + e.getMessage());
            result.completeExceptionally(e);
        }
    }

    private void onButton(JSONObject button) {
        if (busy || result.isDone()) return;
        try {
            if (button.optBoolean("cancel", false)) {
                result.complete(null);
                return;
            }
            String event = new JSONObject().put("button", button.optString("id")).put("values", values()).toString();
            if (callback == null) {
                result.complete(event);
                return;
            }
            busy = true;
            setButtonsEnabled(false);
            CompletableFuture<String> action = callback.apply(event);
            if (action == null) action = CompletableFuture.completedFuture(null);
            action.whenComplete((patch, error) -> App.post(() -> {
                busy = false;
                setButtonsEnabled(true);
                if (result.isDone()) return;
                if (error != null) {
                    result.completeExceptionally(error);
                    return;
                }
                try {
                    if (applyPatch(patch == null ? new JSONObject() : new JSONObject(patch), button.optBoolean("close", true))) result.complete(event);
                } catch (Exception e) {
                    result.completeExceptionally(e);
                }
            }));
        } catch (Exception e) {
            result.completeExceptionally(e);
        }
    }

    /** True when the dialog should close now. */
    private boolean applyPatch(JSONObject patch, boolean close) throws Exception {
        if (patch.has("qrcode")) setQRCode(patch.optString("qrcode"));
        if (patch.has("message")) setMessage(patch.optString("message"));
        if (patch.has("image")) setImage(patch.optString("image"));
        JSONObject values = patch.optJSONObject("values");
        if (values != null) {
            for (Iterator<String> it = values.keys(); it.hasNext(); ) {
                String name = it.next();
                Object input = inputs.get(name);
                if (input instanceof EditText edit) edit.setText(values.optString(name));
            }
        }
        boolean invalid = false;
        JSONObject errors = patch.optJSONObject("errors");
        if (errors != null) {
            for (Iterator<String> it = errors.keys(); it.hasNext(); ) {
                String name = it.next();
                String error = errors.isNull(name) ? null : errors.optString(name);
                Object input = inputs.get(name);
                if (input instanceof EditText edit) edit.setError(error == null || error.isEmpty() ? null : error);
                if (error != null && !error.isEmpty()) invalid = true;
            }
        }
        return patch.optBoolean("close", close) && !invalid;
    }

    private JSONObject values() throws Exception {
        JSONObject values = new JSONObject();
        for (Map.Entry<String, Object> entry : inputs.entrySet()) {
            Object input = entry.getValue();
            if (input instanceof EditText edit) values.put(entry.getKey(), edit.getText().toString());
            else if (input instanceof Spinner spinner) {
                Choice choice = (Choice) spinner.getSelectedItem();
                values.put(entry.getKey(), choice == null ? "" : choice.value);
            } else if (input instanceof List<?> boxes) {
                JSONArray checked = new JSONArray();
                for (Object box : boxes) if (box instanceof CheckBox check && check.isChecked()) checked.put(String.valueOf(check.getTag()));
                values.put(entry.getKey(), checked);
            }
        }
        return values;
    }

    private void addInput(Activity activity, LinearLayout content, JSONObject field) throws Exception {
        String name = field.getString("name");
        String type = field.optString("type", "text");
        TextView label = new TextView(activity);
        label.setText(field.optString("label", name) + (field.optString("helperText").isEmpty() ? "" : "（" + field.optString("helperText") + "）"));
        content.addView(label);
        if (type.equals("select") || type.equals("multiselect")) {
            JSONArray options = field.optJSONArray("options");
            List<Choice> choices = new ArrayList<>();
            if (options != null) for (int i = 0; i < options.length(); i++) {
                JSONObject option = options.getJSONObject(i);
                choices.add(new Choice(option.optString("value"), option.optString("label", option.optString("value"))));
            }
            if (type.equals("select")) {
                Spinner spinner = new Spinner(activity);
                spinner.setAdapter(new ArrayAdapter<>(activity, android.R.layout.simple_spinner_dropdown_item, choices));
                String value = field.optString("value");
                for (int i = 0; i < choices.size(); i++) if (choices.get(i).value.equals(value)) spinner.setSelection(i);
                content.addView(spinner);
                inputs.put(name, spinner);
            } else {
                JSONArray value = field.optJSONArray("value");
                List<CheckBox> boxes = new ArrayList<>();
                for (Choice choice : choices) {
                    CheckBox box = new CheckBox(activity);
                    box.setText(choice.label);
                    box.setTag(choice.value);
                    if (value != null) for (int i = 0; i < value.length(); i++) if (choice.value.equals(value.optString(i))) box.setChecked(true);
                    content.addView(box);
                    boxes.add(box);
                }
                inputs.put(name, boxes);
            }
            return;
        }
        EditText edit = new EditText(activity);
        edit.setText(field.optString("value"));
        edit.setInputType(switch (type) {
            case "textarea" -> InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE;
            case "phone" -> InputType.TYPE_CLASS_PHONE;
            case "number" -> InputType.TYPE_CLASS_NUMBER;
            case "password" -> InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD;
            default -> InputType.TYPE_CLASS_TEXT;
        });
        if (!type.equals("textarea")) edit.setSingleLine(true);
        else edit.setMinLines(3);
        content.addView(edit, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        inputs.put(name, edit);
    }

    private void setButtonsEnabled(boolean enabled) {
        if (dialog == null) return;
        for (Map.Entry<Integer, JSONObject> entry : buttons.entrySet()) {
            Button view = dialog.getButton(entry.getKey());
            if (view != null) view.setEnabled(enabled && entry.getValue().optBoolean("enabled", true));
        }
    }

    private void setMessage(String text) {
        message.setText(text);
        message.setVisibility(text == null || text.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void setQRCode(String text) {
        if (text == null || text.isEmpty()) {
            qrcode.setVisibility(View.GONE);
            return;
        }
        Bitmap bitmap = qr(text, 600);
        qrcode.setImageBitmap(bitmap);
        qrcode.setVisibility(bitmap == null ? View.GONE : View.VISIBLE);
    }

    private void setImage(String value) {
        Bitmap bitmap = dataImage(value);
        image.setImageBitmap(bitmap);
        image.setVisibility(bitmap == null ? View.GONE : View.VISIBLE);
    }

    /** data:image/png;base64,.... -> bitmap, or null. */
    static Bitmap dataImage(String value) {
        if (value == null || !value.startsWith("data:image/")) return null;
        int comma = value.indexOf(";base64,");
        if (comma < 0) return null;
        try {
            byte[] bytes = Base64.decode(value.substring(comma + 8), Base64.DEFAULT);
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** QR code bitmap with zxing core (shipped by :catvod). */
    static Bitmap qr(String text, int size) {
        try {
            Map<EncodeHintType, Object> hints = new HashMap<>();
            hints.put(EncodeHintType.MARGIN, 2);
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            BitMatrix matrix = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints);
            int w = matrix.getWidth();
            int h = matrix.getHeight();
            int[] pixels = new int[w * h];
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) pixels[y * w + x] = matrix.get(x, y) ? Color.BLACK : Color.WHITE;
            return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888);
        } catch (Exception e) {
            return null;
        }
    }

    private static ImageView addImage(Activity activity, LinearLayout content) {
        ImageView view = new ImageView(activity);
        view.setVisibility(View.GONE);
        view.setAdjustViewBounds(true);
        view.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 260));
        params.topMargin = params.bottomMargin = dp(activity, 8);
        content.addView(view, params);
        return view;
    }

    private static int dp(Activity activity, int value) {
        return (int) (value * activity.getResources().getDisplayMetrics().density);
    }

    private void close() {
        if (dialog != null && dialog.isShowing()) {
            try {
                dialog.dismiss();
            } catch (RuntimeException ignored) {
            }
        }
        dialog = null;
    }

    private record Choice(String value, String label) {
        @Override
        public String toString() {
            return label;
        }
    }
}
