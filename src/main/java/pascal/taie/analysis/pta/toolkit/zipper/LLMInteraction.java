package pascal.taie.analysis.pta.toolkit.zipper;

import com.volcengine.ark.runtime.model.completion.chat.ChatCompletionRequest;
import com.volcengine.ark.runtime.model.completion.chat.ChatMessage;
import com.volcengine.ark.runtime.model.completion.chat.ChatMessageRole;
import com.volcengine.ark.runtime.service.ArkService;
import okhttp3.ConnectionPool;
import okhttp3.Dispatcher;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class LLMInteraction {
    private final static String apiKey = System.getenv("ARK_API_KEY");

    private static ConnectionPool connectionPool = new ConnectionPool(5, 1, TimeUnit.SECONDS);

    private static Dispatcher dispatcher = new Dispatcher();

    private static ArkService service = ArkService.builder()
            .apiKey(apiKey)
            .build();

    private final static String DEEPSEEK_V3 = "deepseek-v3-250324";

    public static String query(String ir, String type) {
        List<ChatMessage> messagesForReqList = new ArrayList<>();

        String prompt = PromptGeneration.generate(ir, type);
//        String prompt = "Hello, this is good.";
        ChatMessage elementForMessageForReqList0 = ChatMessage.builder()
                .role(ChatMessageRole.USER)
                .content(prompt)
                .build();
        messagesForReqList.add(elementForMessageForReqList0);

        ChatCompletionRequest req = ChatCompletionRequest.builder()
                .model(DEEPSEEK_V3)
                .messages(messagesForReqList)
                .build();

//        System.out.println("Start chat.");
//        System.out.println(prompt.length());
        service.createChatCompletion(req)
                .getChoices()
                .forEach(choice -> postprocess(choice.getMessage().stringContent()));

        String output = service.createChatCompletion(req)
                .getChoices()
                .get(0)
                .getMessage()
                .stringContent();

        String result = postprocess(output);

        System.out.println("Finish chat.");

        service.shutdownExecutor();

        return result;
    }

    private static String postprocess(String output) {
        String startTag = "<FINAL_ANSWER>";
        String endTag = "</FINAL_ANSWER>";

        int startIndex = output.indexOf(startTag);
        int endIndex = output.indexOf(endTag);

        if (startIndex != -1 && endIndex != -1 && endIndex > startIndex) {
            int contentStart = startIndex + startTag.length();
            String content = output.substring(contentStart, endIndex).trim();
            return content;
        } else {
            return null;
        }
    }

    public static void test() {
        List<ChatMessage> messagesForReqList = new ArrayList<>();

        String prompt = "hello";

        ChatMessage elementForMessageForReqList0 = ChatMessage.builder()
                .role(ChatMessageRole.USER)
                .content(prompt)
                .build();
        messagesForReqList.add(elementForMessageForReqList0);

        ChatCompletionRequest req = ChatCompletionRequest.builder()
                .model(DEEPSEEK_V3)
                .messages(messagesForReqList)
                .build();

        System.out.println("Start chat.");
        System.out.println(prompt.length());

        service.createChatCompletion(req)
                .getChoices()
                .forEach(choice -> postprocess(choice.getMessage().stringContent()));

        System.out.println("Finish chat.");

        service.shutdownExecutor();
    }


}
