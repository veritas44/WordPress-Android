package org.wordpress.android.ui.comments;

import android.app.AlertDialog;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Bundle;
import android.support.v7.app.ActionBar;
import android.support.v7.app.AppCompatActivity;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.ProgressBar;

import com.android.volley.VolleyError;
import com.wordpress.rest.RestRequest;

import org.json.JSONObject;
import org.w3c.dom.Comment;
import org.wordpress.android.R;
import org.wordpress.android.WordPress;
import org.wordpress.android.datasets.NotificationsTable;
import org.wordpress.android.fluxc.Dispatcher;
import org.wordpress.android.fluxc.model.CommentModel;
import org.wordpress.android.fluxc.model.CommentStatus;
import org.wordpress.android.fluxc.model.SiteModel;
import org.wordpress.android.fluxc.store.CommentStore;
import org.wordpress.android.fluxc.store.SiteStore;
import org.wordpress.android.models.Note;
import org.wordpress.android.ui.ActivityId;
import org.wordpress.android.util.AppLog;
import org.wordpress.android.util.EditTextUtils;
import org.wordpress.android.util.NetworkUtils;
import org.wordpress.android.util.StringUtils;
import org.wordpress.android.util.ToastUtils;
import org.wordpress.android.util.VolleyUtils;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlrpc.android.ApiHelper.Method;
import org.xmlrpc.android.XMLRPCClientInterface;
import org.xmlrpc.android.XMLRPCException;
import org.xmlrpc.android.XMLRPCFactory;

import java.io.IOException;
import java.net.URI;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import javax.inject.Inject;

public class EditCommentActivity extends AppCompatActivity {
    static final String ARG_COMMENT = "ARG_COMMENT";
    static final String ARG_NOTE_ID = "ARG_NOTE_ID";

    private static final int ID_DIALOG_SAVING = 0;

    private SiteModel mSite;
    private CommentModel mComment;
    private Note mNote;

    @Inject Dispatcher mDispatcher;
    @Inject SiteStore mSiteStore;
    @Inject CommentStore mCommentStore;

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        ((WordPress) getApplication()).component().inject(this);

        setContentView(R.layout.comment_edit_activity);
        setTitle(getString(R.string.edit_comment));

        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayShowTitleEnabled(true);
            actionBar.setDisplayHomeAsUpEnabled(true);
        }

        loadComment(getIntent());

        ActivityId.trackLastActivity(ActivityId.COMMENT_EDITOR);
    }

    @Override
    public void onStart() {
        super.onStart();
        mDispatcher.register(this);
    }

    @Override
    public void onStop() {
        mDispatcher.unregister(this);
        super.onStop();
    }

    private void loadComment(Intent intent) {
        if (intent == null) {
            showErrorAndFinish();
            return;
        }

        mSite = (SiteModel) intent.getSerializableExtra(WordPress.SITE);
        mComment = (CommentModel) intent.getSerializableExtra(ARG_COMMENT);
        final String noteId = intent.getStringExtra(ARG_NOTE_ID);
        if (noteId == null) {
            if (mComment == null) {
                showErrorAndFinish();
                return;
            }
            configureViews();
        } else {
            mNote = NotificationsTable.getNoteById(noteId);
            if (mNote != null) {
                requestFullCommentForNote(mNote);
            } else {
                showErrorAndFinish();
            }
        }
    }

    private void showErrorAndFinish() {
        ToastUtils.showToast(this, R.string.error_load_comment);
        finish();
    }

    private void configureViews() {
        final EditText editAuthorName = (EditText) this.findViewById(R.id.author_name);
        editAuthorName.setText(mComment.getAuthorName());

        final EditText editAuthorEmail = (EditText) this.findViewById(R.id.author_email);
        editAuthorEmail.setText(mComment.getAuthorEmail());

        final EditText editAuthorUrl = (EditText) this.findViewById(R.id.author_url);
        editAuthorUrl.setText(mComment.getAuthorUrl());

        // REST API can currently only edit comment content
        if (mNote != null) {
            editAuthorName.setVisibility(View.GONE);
            editAuthorEmail.setVisibility(View.GONE);
            editAuthorUrl.setVisibility(View.GONE);
        }

        final EditText editContent = (EditText) this.findViewById(R.id.edit_comment_content);
        editContent.setText(mComment.getContent());

        // show error when comment content is empty
        editContent.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }
            @Override
            public void afterTextChanged(Editable s) {
                boolean hasError = (editContent.getError() != null);
                boolean hasText = (s != null && s.length() > 0);
                if (!hasText && !hasError) {
                    editContent.setError(getString(R.string.content_required));
                } else if (hasText && hasError) {
                    editContent.setError(null);
                }
            }
        });
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.edit_comment, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(final MenuItem item) {
        int i = item.getItemId();
        if (i == android.R.id.home) {
            onBackPressed();
            return true;
        } else if (i == R.id.menu_save_comment) {
            saveComment();
            return true;
        } else {
            return super.onOptionsItemSelected(item);
        }
    }

    private String getEditTextStr(int resId) {
        final EditText edit = (EditText) findViewById(resId);
        return EditTextUtils.getText(edit);
    }

    private void saveComment() {
        // make sure comment content was entered
        final EditText editContent = (EditText) findViewById(R.id.edit_comment_content);
        if (EditTextUtils.isEmpty(editContent)) {
            editContent.setError(getString(R.string.content_required));
            return;
        }

        // return immediately if comment hasn't changed
        if (!isCommentEdited()) {
            ToastUtils.showToast(this, R.string.toast_comment_unedited);
            return;
        }

        // make sure we have an active connection
        if (!NetworkUtils.checkConnection(this))
            return;

        if (mNote != null) {
            // Edit comment via REST API :)
            showSaveDialog();
            // FIXME: replace the following
            WordPress.getRestClientUtils().editCommentContent(mNote.getSiteId(),
                    mNote.getCommentId(),
                    EditTextUtils.getText(editContent),
                    new RestRequest.Listener() {
                        @Override
                        public void onResponse(JSONObject response) {
                            if (isFinishing()) return;

                            dismissSaveDialog();
                            setResult(RESULT_OK);
                            finish();
                        }
                    }, new RestRequest.ErrorListener() {
                        @Override
                        public void onErrorResponse(VolleyError error) {
                            if (isFinishing()) return;

                            dismissSaveDialog();
                            showEditErrorAlert();
                        }
                    });
        } else {
            // Edit comment via XML-RPC :(
            // FIXME: replace the following
            if (mIsUpdateTaskRunning)
                AppLog.w(AppLog.T.COMMENTS, "update task already running");
            new UpdateCommentTask().execute();
        }
    }

    /*
     * returns true if user made any changes to the comment
     */
    private boolean isCommentEdited() {
        if (mComment == null)
            return false;

        final String authorName = getEditTextStr(R.id.author_name);
        final String authorEmail = getEditTextStr(R.id.author_email);
        final String authorUrl = getEditTextStr(R.id.author_url);
        final String content = getEditTextStr(R.id.edit_comment_content);

        return !(authorName.equals(mComment.getAuthorName())
                && authorEmail.equals(mComment.getAuthorEmail())
                && authorUrl.equals(mComment.getAuthorUrl())
                && content.equals(mComment.getContent()));
    }

    @Override
    protected Dialog onCreateDialog(int id) {
        if (id == ID_DIALOG_SAVING) {
            ProgressDialog savingDialog = new ProgressDialog(this);
            savingDialog.setMessage(getResources().getText(R.string.saving_changes));
            savingDialog.setIndeterminate(true);
            savingDialog.setCancelable(true);
            return savingDialog;
        }
        return super.onCreateDialog(id);
    }

    private void showSaveDialog() {
        showDialog(ID_DIALOG_SAVING);
    }

    private void dismissSaveDialog() {
        try {
            dismissDialog(ID_DIALOG_SAVING);
        } catch (IllegalArgumentException e) {
            // dialog doesn't exist
        }
    }

    private void showEditErrorAlert() {
        AlertDialog.Builder dialogBuilder = new AlertDialog.Builder(EditCommentActivity.this);
        dialogBuilder.setTitle(getResources().getText(R.string.error));
        dialogBuilder.setMessage(R.string.error_edit_comment);
        dialogBuilder.setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int whichButton) {
                // just close the dialog
            }
        });
        dialogBuilder.setCancelable(true);
        dialogBuilder.create().show();
    }

    // Request a comment via the REST API for a note
    private void requestFullCommentForNote(Note note) {
        if (isFinishing()) return;
        final ProgressBar progress = (ProgressBar)findViewById(R.id.edit_comment_progress);
        final View editContainer = findViewById(R.id.edit_comment_container);

        if (progress == null || editContainer == null) {
            return;
        }

        editContainer.setVisibility(View.GONE);
        progress.setVisibility(View.VISIBLE);

        RestRequest.Listener restListener = new RestRequest.Listener() {
            @Override
            public void onResponse(JSONObject jsonObject) {
                if (!isFinishing()) {
                    progress.setVisibility(View.GONE);
                    editContainer.setVisibility(View.VISIBLE);
                    Comment comment = Comment.fromJSON(jsonObject);
                    if (comment != null) {
                        mComment = comment;
                        configureViews();
                    } else {
                        showErrorAndFinish();
                    }
                }
            }
        };
        RestRequest.ErrorListener restErrListener = new RestRequest.ErrorListener() {
            @Override
            public void onErrorResponse(VolleyError volleyError) {
                AppLog.e(AppLog.T.COMMENTS, VolleyUtils.errStringFromVolleyError(volleyError), volleyError);
                if (!isFinishing()) {
                    progress.setVisibility(View.GONE);
                    showErrorAndFinish();
                }
            }
        };

        final String path = String.format(Locale.US, "/sites/%s/comments/%s", note.getSiteId(), note.getCommentId());
        WordPress.getRestClientUtils().get(path, restListener, restErrListener);
    }

    @Override
    public void onBackPressed() {
        if (isCommentEdited()) {
            AlertDialog.Builder dialogBuilder = new AlertDialog.Builder(
                    EditCommentActivity.this);
            dialogBuilder.setTitle(getResources().getText(R.string.cancel_edit));
            dialogBuilder.setMessage(getResources().getText(R.string.sure_to_cancel_edit_comment));
            dialogBuilder.setPositiveButton(getResources().getText(R.string.yes),
                    new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int whichButton) {
                            finish();
                        }
                    });
            dialogBuilder.setNegativeButton(
                    getResources().getText(R.string.no),
                    new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int whichButton) {
                            // just close the dialog
                        }
                    });
            dialogBuilder.setCancelable(true);
            dialogBuilder.create().show();
        } else {
            super.onBackPressed();
        }
    }
}
