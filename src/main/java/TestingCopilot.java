import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class TestingCopilot {
    private int id;
    private String name;
    private List<String> list;

    /** get Fabonacci number */
    public static int getFabonacci(int n) {
        if (n <= 1) {
            return n;
        } else {
            return getFabonacci(n - 1) + getFabonacci(n - 2);
        }
    }

    public TestingCopilot()  {
        this.id = 0;
        this.name = "";
        this.list = new ArrayList<String>();
    }
    public static void main(String[] args) {
        TestingCopilot tc = new TestingCopilot();
        tc.setId(1);
        tc.setName("test");
        tc.addToList("test1");
        tc.addToList("test2");
        tc.addToList("test3");
        tc.addToList("test4");
        JSONObject json = tc.getJSONObject();
        //System.out.println(json.toString(2));

        JSONObject json2 = new JSONObject();
        json2.put("id", 1);
        json2.put("assetGUID", "test");
        // insert an array of JSONObjects
        List<JSONObject> list = new ArrayList<JSONObject>();
        list.add(new JSONObject().put("assetGUID", "test1").put("assetName", "test1name"));
        list.add(new JSONObject().put("assetGUID", "test2").put("assetName", "test2name"));
        list.add(new JSONObject().put("assetGUID", "test3").put("assetName", "test3name"));
        json2.put("list", list);
        StringBuffer sb = new StringBuffer();
        extractJSONFields(json2,  "\n", sb, "id", "assetGUID", "assetName");
        System.out.println(sb.toString());
    }

    private void addToList(String test1) {
        this.list.add(test1);
    }

    private void setName(String test) {
        this.name = test;
    }

    private void setId(int i) {
        this.id = i;
    }

    /**
     * Check hits and misses overflow, reset stats if needed, and save reset time.
     */
    public static void bidding(int biddingAmount1, int biddingAmount2) {
        if (biddingAmount1 > biddingAmount2) {
            System.out.println("bidder 1 winds");
        } else if (biddingAmount2 > biddingAmount1) {
            System.out.println("bidder 2 winds");
        } else {
            System.out.println("tie");
        }
    }

    /**
     * a method to get total price, including tax, given inputs of price and tax rate.
     * with the result rounded to 2 decimal points.
     */
    public static double getTotalPrice(double price, double taxRate) {
        double totalPrice = price * (1 + taxRate);
        // round to 2 decimal points
        totalPrice = Math.round(totalPrice * 100.0) / 100.0;
        return totalPrice;
    }

    /**
     * a method to get name object from any JSONObject, and name could be embedded in a nested JSONObject.
     *
     * @param json      a JSONObject
     * @return  JSONObject
     */
    public static JSONObject getName(JSONObject json) {
        JSONObject name = null;
        if (json.has("name")) {
            name = json.getJSONObject("name");
        } else if (json.has("person")) {
            JSONObject person = json.getJSONObject("person");
            if (person.has("name")) {
                name = person.getJSONObject("name");
            }
        } else if (json.has("user")) {
            JSONObject user = json.getJSONObject("user");
            if (user.has("name")) {
                name = user.getJSONObject("name");
            }
        } else if (json.has("customer")) {
            JSONObject customer = json.getJSONObject("customer");
            if (customer.has("name")) {
                name = customer.getJSONObject("name");
            }
        } else if (json.has("profile")) {
            JSONObject profile = json.getJSONObject("profile");
            if (profile.has("name")) {
                name = profile.getJSONObject("name");
            }
        } else if (json.has("account")) {
            JSONObject account = json.getJSONObject("account");
            if (account.has("name")) {
                name = account.getJSONObject("name");
            }
        } else if (json.has("person")) {
            JSONObject person = json.getJSONObject("person");
            if (person.has("name")) {
                name = person.getJSONObject("name");
            }
        }
        return name;
    }


    public static int getRandomNumber() {
        return (int) (Math.random() * 50) + 1;
    }

    /** a mehtod with input string, and return a substring between a and z
     * @param input
     */
    public static String getSubString(String input) {
        int a = input.indexOf("a");
        int z = input.indexOf("z");
        return input.substring(a, z);
    }

    public static void printList(List<String> list) {
        for (String s : list) {
            System.out.println(s);
        }
    }
    /**
     * a method to return JSONObject from current TestingCopilot object
     * @return  JSONObject
     */
    public JSONObject getJSONObject() {
        JSONObject obj = new JSONObject();
        obj.put("id", id);
        obj.put("name", name);
        obj.put("list", list);
        return obj;
    }

    /**
     * a method to extract fields from a JSONObject, and append to a StringBuffer
     * @param json
     * @param separator
     * @param sb
     * @param fields
     */
    public static void extractJSONFields(JSONObject json, String newline, StringBuffer sb, String... keys) {
        for (String key : keys) {
            if (json.has(key)) {
                sb.append(key + "=").append(json.get(key)).append(", ").append(newline);
            }
        }
        for (String key : json.keySet()) {
            Object value = json.get(key);
            if (value instanceof JSONObject) {
                extractJSONFields((JSONObject)value, newline, sb, keys);
            } else if (value instanceof JSONArray) {
                JSONArray array = (JSONArray) value;
                sb.append("[ ");
                for (int i = 0; i < array.length(); i++) {
                    Object obj = array.get(i);
                    if (obj instanceof JSONObject) {
                        extractJSONFields((JSONObject) obj, "", sb, keys);
                    }
                }
                sb.append(" ]").append(newline);
            }
        }
    }

}
