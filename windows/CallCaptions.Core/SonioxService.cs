using System.Net.WebSockets;
using System.Text.Json;

namespace CallCaptions.Core;

// The configuration and original/translation routing follow My Translator's MIT client.
public sealed class SonioxService : ICaptionService
{
    public static readonly Uri DefaultEndpoint = new("wss://stt-rt.soniox.com/transcribe-websocket");

    private readonly Uri _endpoint;
    private readonly ClientWebSocket _socket;
    private readonly CancellationTokenSource _cts = new();
    private readonly SemaphoreSlim _sendLock = new(1, 1);
    private Task? _reader;

    public SonioxService(string key, Uri? endpoint = null)
    {
        _endpoint = endpoint ?? DefaultEndpoint;
        _socket = WebSocketHelpers.CreateSocket("Authorization", "Bearer " + key);
    }

    public async Task StartAsync(
        Action<IReadOnlyList<Token>> receive,
        Action<Exception> failed,
        CancellationToken cancellationToken = default)
    {
        using var linked = CancellationTokenSource.CreateLinkedTokenSource(_cts.Token, cancellationToken);
        var config = new
        {
            model = "stt-rt-v6",
            audio_format = "pcm_s16le",
            sample_rate = 16000,
            num_channels = 1,
            language_hints = new[] { "ja" },
            enable_endpoint_detection = true,
            translation = new
            {
                type = "one_way",
                target_language = "zh"
            }
        };
        string json = JsonSerializer.Serialize(config);

        try
        {
            await _socket.ConnectAsync(_endpoint, linked.Token).ConfigureAwait(false);
            await WebSocketHelpers.SendTextAsync(_socket, json, linked.Token).ConfigureAwait(false);
            linked.Token.ThrowIfCancellationRequested();
        }
        catch (Exception ex)
        {
            throw WebSocketHelpers.Explain(Provider.Soniox, _socket, ex);
        }

        _reader = Task.Run(async () =>
        {
            try
            {
                while (!_cts.IsCancellationRequested)
                {
                    byte[] data = await WebSocketHelpers.ReceiveFullMessageAsync(_socket, Provider.Soniox, _cts.Token)
                        .ConfigureAwait(false);
                    var reply = JsonSerializer.Deserialize<Reply>(data);
                    if (reply is null) continue;

                    if (reply.ErrorCode.HasValue || !string.IsNullOrEmpty(reply.ErrorMessage))
                    {
                        throw ServiceErrorClassifier.Classify(
                            provider: Provider.Soniox,
                            errorCode: reply.ErrorCode,
                            errorMessage: reply.ErrorMessage);
                    }

                    if (reply.Tokens is not null)
                    {
                        receive(reply.Tokens);
                    }

                    if (reply.Finished == true)
                    {
                        throw new CaptionError("Soniox 会话已结束。", CaptionErrorKind.Transient);
                    }
                }
            }
            catch (Exception ex)
            {
                if (!_cts.IsCancellationRequested)
                {
                    failed(WebSocketHelpers.Explain(Provider.Soniox, _socket, ex));
                }
            }
        });
    }

    public async Task SendAsync(byte[] data, CancellationToken cancellationToken = default)
    {
        using var linked = CancellationTokenSource.CreateLinkedTokenSource(_cts.Token, cancellationToken);
        await _sendLock.WaitAsync(linked.Token).ConfigureAwait(false);
        try
        {
            await _socket.SendAsync(new ArraySegment<byte>(data), WebSocketMessageType.Binary, endOfMessage: true, linked.Token)
                .ConfigureAwait(false);
        }
        catch (Exception ex)
        {
            throw WebSocketHelpers.Explain(Provider.Soniox, _socket, ex);
        }
        finally
        {
            _sendLock.Release();
        }
    }

    public void Close()
    {
        if (_cts.IsCancellationRequested) return;
        _cts.Cancel();
        try
        {
            if (_socket.State is WebSocketState.Open or WebSocketState.CloseReceived)
            {
                _socket.Abort();
            }
        }
        catch
        {
            // Ignore errors on teardown.
        }
        finally
        {
            _socket.Dispose();
        }
    }
}
