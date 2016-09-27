package clarifai2.api.request;

import clarifai2.api.ClarifaiResponse;
import clarifai2.dto.ClarifaiStatus;
import clarifai2.exception.ClarifaiException;
import clarifai2.internal.JSONUnmarshaler;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;

final class ClarifaiRequestImpl<RESULT> implements ClarifaiRequest<RESULT> {

  @NotNull private final Gson gson;
  @NotNull private final OkHttpClient client;
  @NotNull private final Request request;
  @NotNull private final JSONUnmarshaler<RESULT> unmarshaler;

  ClarifaiRequestImpl(
      @NotNull Gson gson,
      @NotNull OkHttpClient client,
      @NotNull Request request,
      @NotNull JSONUnmarshaler<RESULT> unmarshaler
  ) {
    this.gson = gson;
    this.client = client;
    this.request = request;
    this.unmarshaler = unmarshaler;
  }

  @NotNull
  @Override
  public ClarifaiResponse<RESULT> executeSync() {
    try {
      return getClarifaiResponse(client.newCall(request).execute());
    } catch (IOException e) {
      return ClarifaiResponse.create(ClarifaiStatus.networkError(), 0, null, null);
    }
  }

  @Override
  public void executeAsync(@Nullable final Callback<RESULT> callback) {
    client.newCall(request).enqueue(new AsyncCallback<RESULT>(callback) {
      @Override ClarifaiResponse<RESULT> getClarifaiResponse(@NotNull final Response response) {
        return ClarifaiRequestImpl.this.getClarifaiResponse(response);
      }
    });
  }

  @Override public void executeAsync(@Nullable final OnSuccess<RESULT> onSuccess) throws ClarifaiException {
    executeAsync(onSuccess, null);
  }

  @Override
  public void executeAsync(
      @Nullable final OnSuccess<RESULT> onSuccess,
      @Nullable final OnFailure onFailure
  ) {
    executeAsync(onSuccess, onFailure, null);
  }

  @Override
  public void executeAsync(
      @Nullable final OnSuccess<RESULT> onSuccess,
      @Nullable final OnFailure onFailure,
      @Nullable final OnNetworkError onNetworkError
  ) {
    executeAsync(new Callback<RESULT>() {
      @Override public void onClarifaiResponseSuccess(@NotNull final RESULT result) {
        if (onSuccess != null) {
          onSuccess.onClarifaiResponseSuccess(result);
        }
      }

      @Override public void onClarifaiResponseUnsuccessful(int errorCode) {
        if (onFailure == null) {
          throw new ClarifaiException(
              "Unsuccessful response from Clarifai API was not handled. Error code: " + errorCode
          );
        } else {
          onFailure.onClarifaiResponseUnsuccessful(errorCode);
        }
      }

      @Override public void onClarifaiResponseNetworkError(@NotNull final IOException e) {
        if (onNetworkError == null) {
          throw new ClarifaiException("Network error while contacting Clarifai API was not handled.", e);
        } else {
          onNetworkError.onClarifaiResponseNetworkError(e);
        }
      }
    });
  }

  @NotNull private ClarifaiResponse<RESULT> getClarifaiResponse(@NotNull final Response response) {
    final String rawJSON;
    try {
      rawJSON = response.body().string();
    } catch (IOException e) {
      throw new ClarifaiException(e);
    }
    final JsonObject root;
    final ClarifaiStatus status;
    try {
      root = gson.fromJson(rawJSON, JsonElement.class).getAsJsonObject();
      status = gson.fromJson(root.getAsJsonObject("status"), ClarifaiStatus.class);
    } catch (JsonSyntaxException e) {
      return ClarifaiResponse.create(
          ClarifaiStatus.networkError(),
          0,
          null,
          null
      );
    }

    final int code = response.code();

    final boolean success = 200 <= code && code < 300;

    return ClarifaiResponse.create(
        status,
        code,
        rawJSON,
        success ? unmarshaler.fromJSON(gson, root) : null
    );
  }

  static abstract class AsyncCallback<RESULT> implements okhttp3.Callback {
    @Nullable private final Callback<RESULT> callback;

    private AsyncCallback(@Nullable Callback<RESULT> callback) {
      this.callback = callback;
    }

    @Override
    public void onFailure(Call call, IOException e) {
      if (callback != null) {
        callback.onClarifaiResponseNetworkError(e);
      }
    }

    @Override
    public void onResponse(Call call, Response response) throws IOException {
      final ClarifaiResponse<RESULT> clarifaiResponse = getClarifaiResponse(response);
      if (callback != null) {
        if (response.isSuccessful()) {
          callback.onClarifaiResponseSuccess(clarifaiResponse.get());
        } else {
          callback.onClarifaiResponseUnsuccessful(clarifaiResponse.responseCode());
        }
      }
    }

    abstract ClarifaiResponse<RESULT> getClarifaiResponse(@NotNull Response response);
  }

}
