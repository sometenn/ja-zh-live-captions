using System.Net;
using System.Net.WebSockets;
using System.Text;

namespace CallCaptions.Core;

internal static class WebSocketHelpers
{
    public static ClientWebSocket CreateSocket(string headerName, string headerValue)
    {
        var socket = new ClientWebSocket();
        socket.Options.CollectHttpResponseDetails = true;
        socket.Options.SetRequestHeader(headerName, headerValue);
        return socket;
    }

    public static async Task<byte[]> ReceiveFullMessageAsync(
        ClientWebSocket socket,
        Provider provider,
        CancellationToken cancellationToken)
    {
        var buffer = new byte[16 * 1024];
        using var ms = new MemoryStream();
        while (true)
        {
            var result = await socket.ReceiveAsync(new ArraySegment<byte>(buffer), cancellationToken).ConfigureAwait(false);
            if (result.MessageType == WebSocketMessageType.Close)
            {
                int? closeCode = socket.CloseStatus.HasValue ? (int)socket.CloseStatus.Value : null;
                throw ServiceErrorClassifier.Classify(
                    provider: provider,
                    closeCode: closeCode is null or 0 ? null : closeCode,
                    closeReason: socket.CloseStatusDescription);
            }

            if (result.Count > 0)
            {
                ms.Write(buffer, 0, result.Count);
            }

            if (result.EndOfMessage)
            {
                return ms.ToArray();
            }
        }
    }

    public static Task SendTextAsync(ClientWebSocket socket, string text, CancellationToken cancellationToken)
    {
        byte[] bytes = Encoding.UTF8.GetBytes(text);
        return socket.SendAsync(new ArraySegment<byte>(bytes), WebSocketMessageType.Text, endOfMessage: true, cancellationToken);
    }

    public static Exception Explain(Provider provider, ClientWebSocket socket, Exception error)
    {
        if (error is CaptionError or OperationCanceledException)
        {
            return error;
        }

        int rawStatus = (int)socket.HttpStatusCode;
        int? httpStatus = rawStatus >= 400 ? rawStatus : null;
        int? closeCode = socket.CloseStatus.HasValue ? (int)socket.CloseStatus.Value : null;
        if (closeCode == 0) closeCode = null;
        string? closeReason = socket.CloseStatusDescription;

        return ServiceErrorClassifier.Classify(
            provider: provider,
            httpStatus: httpStatus,
            closeCode: closeCode,
            closeReason: closeReason,
            errorMessage: error.Message);
    }
}
