package com.task12;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.syndicate.deployment.annotations.environment.EnvironmentVariable;
import com.syndicate.deployment.annotations.environment.EnvironmentVariables;
import com.syndicate.deployment.annotations.lambda.LambdaHandler;
import com.syndicate.deployment.annotations.resources.DependsOn;
import com.syndicate.deployment.model.ResourceType;
import com.syndicate.deployment.model.RetentionSetting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminCreateUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminInitiateAuthRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminInitiateAuthResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminSetUserPasswordRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AttributeType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AuthFlowType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.MessageActionType;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import static com.syndicate.deployment.model.environment.ValueTransformer.USER_POOL_NAME_TO_CLIENT_ID;
import static com.syndicate.deployment.model.environment.ValueTransformer.USER_POOL_NAME_TO_USER_POOL_ID;

@LambdaHandler(lambdaName = "api_handler", roleName = "api_handler-role", isPublishVersion = true, aliasName = "${lambdas_alias_name}", logsExpiration = RetentionSetting.SYNDICATE_ALIASES_SPECIFIED)
@DependsOn(resourceType = ResourceType.COGNITO_USER_POOL, name = "${booking_userpool}")
@EnvironmentVariables(value = {
		@EnvironmentVariable(key = "REGION", value = "${region}"),
		@EnvironmentVariable(key = "COGNITO_ID", value = "${booking_userpool}", valueTransformer = USER_POOL_NAME_TO_USER_POOL_ID),
		@EnvironmentVariable(key = "CLIENT_ID", value = "${booking_userpool}", valueTransformer = USER_POOL_NAME_TO_CLIENT_ID),
		@EnvironmentVariable(key = "TABLES_TABLE", value = "${tables_table}"),
		@EnvironmentVariable(key = "RESERVATIONS_TABLE", value = "${reservations_table}")
})
public class ApiHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	// Cognito User Pool ID, env variable'dan geliyor (syndicate dynamic parameter
	// ile dolduruluyor)
	private static final String USER_POOL_ID = System.getenv("COGNITO_ID");

	// Email: basit ama yeterli bir email format kontrolü
	private static final Pattern EMAIL_PATTERN = Pattern.compile("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");

	// Password: alphanumeric + $ % ^ * - _ karakterlerinden en az biri, 12+
	// karakter
	private static final Pattern PASSWORD_PATTERN = Pattern
			.compile("^(?=.*[A-Za-z0-9])(?=.*[$%^*\\-_])[A-Za-z0-9$%^*\\-_]{12,}$");

	// Cognito App Client ID, env variable'dan geliyor
	private static final String CLIENT_ID = System.getenv("CLIENT_ID");

	// DynamoDB tablo isimleri, env variable'lardan geliyor (syndicate dynamic
	// parameter ile dolduruluyor)
	private static final String TABLES_TABLE = System.getenv("TABLES_TABLE");
	private static final String RESERVATIONS_TABLE = System.getenv("RESERVATIONS_TABLE");

	private final CognitoIdentityProviderClient cognitoClient = CognitoIdentityProviderClient.builder().build();
	private final DynamoDbClient dynamoClient = DynamoDbClient.builder().build();

	@Override
	public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent request, Context context) {
		String path = request.getPath();
		String method = request.getHttpMethod();

		try {
			if ("/signup".equals(path) && "POST".equals(method)) {
				return handleSignup(request);
			}
			if ("/signin".equals(path) && "POST".equals(method)) {
				return handleSignin(request);
			}
			if ("/tables".equals(path) && "GET".equals(method)) {
				return handleGetTables();
			}
			if ("/tables".equals(path) && "POST".equals(method)) {
				return handleCreateTable(request);
			}
			if (path.matches("^/tables/[^/]+$") && "GET".equals(method)) {
				String tableId = path.substring("/tables/".length());
				return handleGetTableById(tableId);
			}
			if ("/reservations".equals(path) && "POST".equals(method)) {
				return handleCreateReservation(request);
			}
			if ("/reservations".equals(path) && "GET".equals(method)) {
				return handleGetReservations();
			}

			return buildResponse(404, "{\"message\": \"Not found: " + method + " " + path + "\"}");
		} catch (Exception e) {
			context.getLogger().log("Error: " + e.getMessage());
			return buildResponse(400, "{\"message\": \"" + e.getMessage() + "\"}");
		}
	}

	private APIGatewayProxyResponseEvent handleSignup(APIGatewayProxyRequestEvent request) throws Exception {
		JsonNode body = MAPPER.readTree(request.getBody());

		String firstName = body.path("firstName").asText(null);
		String lastName = body.path("lastName").asText(null);
		String email = body.path("email").asText(null);
		String password = body.path("password").asText(null);

		// Validasyon
		if (firstName == null || firstName.isBlank()
				|| lastName == null || lastName.isBlank()
				|| email == null || !EMAIL_PATTERN.matcher(email).matches()
				|| password == null || !PASSWORD_PATTERN.matcher(password).matches()) {
			return buildResponse(400, "{\"message\": \"Invalid signup request\"}");
		}

		// Cognito'da kullanıcı oluştur - username olarak email kullanıyoruz
		AdminCreateUserRequest createUserRequest = AdminCreateUserRequest.builder()
				.userPoolId(USER_POOL_ID)
				.username(email)
				.userAttributes(
						AttributeType.builder().name("email").value(email).build(),
						AttributeType.builder().name("email_verified").value("true").build(),
						AttributeType.builder().name("given_name").value(firstName).build(),
						AttributeType.builder().name("family_name").value(lastName).build())
				// ÖNEMLİ: email quota hatasını önlemek için doğrulama maili gönderilmesini
				// engelliyoruz
				.messageAction(MessageActionType.SUPPRESS)
				.build();

		cognitoClient.adminCreateUser(createUserRequest);

		// Geçici şifre yerine direkt kalıcı şifre ata
		AdminSetUserPasswordRequest setPasswordRequest = AdminSetUserPasswordRequest.builder()
				.userPoolId(USER_POOL_ID)
				.username(email)
				.password(password)
				.permanent(true)
				.build();

		cognitoClient.adminSetUserPassword(setPasswordRequest);

		return buildResponse(200, "{\"message\": \"Sign-up process is successful\"}");
	}

	private APIGatewayProxyResponseEvent handleSignin(APIGatewayProxyRequestEvent request) throws Exception {
		JsonNode body = MAPPER.readTree(request.getBody());

		String email = body.path("email").asText(null);
		String password = body.path("password").asText(null);

		if (email == null || !EMAIL_PATTERN.matcher(email).matches()
				|| password == null || !PASSWORD_PATTERN.matcher(password).matches()) {
			return buildResponse(400, "{\"message\": \"Invalid signin request\"}");
		}

		Map<String, String> authParams = new HashMap<>();
		authParams.put("USERNAME", email);
		authParams.put("PASSWORD", password);

		AdminInitiateAuthRequest authRequest = AdminInitiateAuthRequest.builder()
				.userPoolId(USER_POOL_ID)
				.clientId(CLIENT_ID)
				.authFlow(AuthFlowType.ADMIN_USER_PASSWORD_AUTH)
				.authParameters(authParams)
				.build();

		AdminInitiateAuthResponse authResponse = cognitoClient.adminInitiateAuth(authRequest);

		String idToken = authResponse.authenticationResult().idToken();

		Map<String, String> responseBody = new HashMap<>();
		responseBody.put("idToken", idToken);

		return buildResponse(200, MAPPER.writeValueAsString(responseBody));
	}

	private APIGatewayProxyResponseEvent handleGetTables() {
		ScanRequest scanRequest = ScanRequest.builder()
				.tableName(TABLES_TABLE)
				.build();

		ScanResponse scanResponse = dynamoClient.scan(scanRequest);

		List<Map<String, Object>> tables = new ArrayList<>();
		for (Map<String, AttributeValue> item : scanResponse.items()) {
			tables.add(tableItemToMap(item));
		}

		Map<String, Object> responseBody = new HashMap<>();
		responseBody.put("tables", tables);

		try {
			return buildResponse(200, MAPPER.writeValueAsString(responseBody));
		} catch (Exception e) {
			return buildResponse(400, "{\"message\": \"" + e.getMessage() + "\"}");
		}
	}

	private APIGatewayProxyResponseEvent handleCreateTable(APIGatewayProxyRequestEvent request) throws Exception {
		JsonNode body = MAPPER.readTree(request.getBody());

		if (!body.hasNonNull("id") || !body.hasNonNull("number")
				|| !body.hasNonNull("places") || !body.hasNonNull("isVip")) {
			return buildResponse(400, "{\"message\": \"Invalid create table request\"}");
		}

		int id = body.get("id").asInt();
		int number = body.get("number").asInt();
		int places = body.get("places").asInt();
		boolean isVip = body.get("isVip").asBoolean();

		Map<String, AttributeValue> item = new HashMap<>();
		item.put("id", AttributeValue.builder().s(String.valueOf(id)).build());
		item.put("number", AttributeValue.builder().n(String.valueOf(number)).build());
		item.put("places", AttributeValue.builder().n(String.valueOf(places)).build());
		item.put("isVip", AttributeValue.builder().bool(isVip).build());

		if (body.hasNonNull("minOrder")) {
			item.put("minOrder", AttributeValue.builder().n(String.valueOf(body.get("minOrder").asInt())).build());
		}

		PutItemRequest putItemRequest = PutItemRequest.builder()
				.tableName(TABLES_TABLE)
				.item(item)
				.build();

		dynamoClient.putItem(putItemRequest);

		Map<String, Object> responseBody = new HashMap<>();
		responseBody.put("id", id);

		return buildResponse(200, MAPPER.writeValueAsString(responseBody));
	}

	private APIGatewayProxyResponseEvent handleGetTableById(String tableId) throws Exception {
		Map<String, AttributeValue> key = new HashMap<>();
		key.put("id", AttributeValue.builder().s(tableId).build());

		GetItemRequest getItemRequest = GetItemRequest.builder()
				.tableName(TABLES_TABLE)
				.key(key)
				.build();

		GetItemResponse getItemResponse = dynamoClient.getItem(getItemRequest);

		if (!getItemResponse.hasItem()) {
			return buildResponse(400, "{\"message\": \"Table not found\"}");
		}

		Map<String, Object> tableMap = tableItemToMap(getItemResponse.item());

		return buildResponse(200, MAPPER.writeValueAsString(tableMap));
	}

	private Map<String, Object> tableItemToMap(Map<String, AttributeValue> item) {
		Map<String, Object> map = new HashMap<>();
		map.put("id", Integer.parseInt(item.get("id").s()));
		map.put("number", Integer.parseInt(item.get("number").n()));
		map.put("places", Integer.parseInt(item.get("places").n()));
		map.put("isVip", item.get("isVip").bool());
		if (item.containsKey("minOrder")) {
			map.put("minOrder", Integer.parseInt(item.get("minOrder").n()));
		}
		return map;
	}

	private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

	private APIGatewayProxyResponseEvent handleCreateReservation(APIGatewayProxyRequestEvent request) throws Exception {
		JsonNode body = MAPPER.readTree(request.getBody());

		if (!body.hasNonNull("tableNumber") || !body.hasNonNull("clientName")
				|| !body.hasNonNull("phoneNumber") || !body.hasNonNull("date")
				|| !body.hasNonNull("slotTimeStart") || !body.hasNonNull("slotTimeEnd")) {
			return buildResponse(400, "{\"message\": \"Invalid reservation request\"}");
		}

		int tableNumber = body.get("tableNumber").asInt();
		String clientName = body.get("clientName").asText();
		String phoneNumber = body.get("phoneNumber").asText();
		String date = body.get("date").asText();
		String slotTimeStartStr = body.get("slotTimeStart").asText();
		String slotTimeEndStr = body.get("slotTimeEnd").asText();

		LocalTime newStart;
		LocalTime newEnd;
		try {
			newStart = LocalTime.parse(slotTimeStartStr, TIME_FORMAT);
			newEnd = LocalTime.parse(slotTimeEndStr, TIME_FORMAT);
		} catch (Exception e) {
			return buildResponse(400, "{\"message\": \"Invalid time format\"}");
		}

		if (!newStart.isBefore(newEnd)) {
			return buildResponse(400, "{\"message\": \"slotTimeStart must be before slotTimeEnd\"}");
		}

		if (!tableExists(tableNumber)) {
			return buildResponse(400, "{\"message\": \"Table not found\"}");
		}

		// Aynı masa, aynı tarih için çakışan rezervasyon var mı kontrol et
		ScanRequest scanRequest = ScanRequest.builder()
				.tableName(RESERVATIONS_TABLE)
				.build();

		ScanResponse scanResponse = dynamoClient.scan(scanRequest);

		for (Map<String, AttributeValue> item : scanResponse.items()) {
			int existingTableNumber = Integer.parseInt(item.get("tableNumber").n());
			String existingDate = item.get("date").s();

			if (existingTableNumber != tableNumber || !existingDate.equals(date)) {
				continue;
			}

			LocalTime existingStart = LocalTime.parse(item.get("slotTimeStart").s(), TIME_FORMAT);
			LocalTime existingEnd = LocalTime.parse(item.get("slotTimeEnd").s(), TIME_FORMAT);

			// İki aralık kesişiyor mu: existingStart < newEnd && newStart < existingEnd
			if (existingStart.isBefore(newEnd) && newStart.isBefore(existingEnd)) {
				return buildResponse(400, "{\"message\": \"Conflicting reservation for this table and time\"}");
			}
		}

		String reservationId = UUID.randomUUID().toString();

		Map<String, AttributeValue> item = new HashMap<>();
		item.put("id", AttributeValue.builder().s(reservationId).build());
		item.put("tableNumber", AttributeValue.builder().n(String.valueOf(tableNumber)).build());
		item.put("clientName", AttributeValue.builder().s(clientName).build());
		item.put("phoneNumber", AttributeValue.builder().s(phoneNumber).build());
		item.put("date", AttributeValue.builder().s(date).build());
		item.put("slotTimeStart", AttributeValue.builder().s(slotTimeStartStr).build());
		item.put("slotTimeEnd", AttributeValue.builder().s(slotTimeEndStr).build());

		PutItemRequest putItemRequest = PutItemRequest.builder()
				.tableName(RESERVATIONS_TABLE)
				.item(item)
				.build();

		dynamoClient.putItem(putItemRequest);

		Map<String, Object> responseBody = new HashMap<>();
		responseBody.put("reservationId", reservationId);

		return buildResponse(200, MAPPER.writeValueAsString(responseBody));
	}

	private APIGatewayProxyResponseEvent handleGetReservations() throws Exception {
		ScanRequest scanRequest = ScanRequest.builder()
				.tableName(RESERVATIONS_TABLE)
				.build();

		ScanResponse scanResponse = dynamoClient.scan(scanRequest);

		List<Map<String, Object>> reservations = new ArrayList<>();
		for (Map<String, AttributeValue> item : scanResponse.items()) {
			reservations.add(reservationItemToMap(item));
		}

		Map<String, Object> responseBody = new HashMap<>();
		responseBody.put("reservations", reservations);

		return buildResponse(200, MAPPER.writeValueAsString(responseBody));
	}

	private boolean tableExists(int tableNumber) {
		ScanRequest scanRequest = ScanRequest.builder()
				.tableName(TABLES_TABLE)
				.build();

		ScanResponse scanResponse = dynamoClient.scan(scanRequest);

		for (Map<String, AttributeValue> item : scanResponse.items()) {
			if (Integer.parseInt(item.get("number").n()) == tableNumber) {
				return true;
			}
		}
		return false;
	}

	private Map<String, Object> reservationItemToMap(Map<String, AttributeValue> item) {
		Map<String, Object> map = new HashMap<>();
		map.put("tableNumber", Integer.parseInt(item.get("tableNumber").n()));
		map.put("clientName", item.get("clientName").s());
		map.put("phoneNumber", item.get("phoneNumber").s());
		map.put("date", item.get("date").s());
		map.put("slotTimeStart", item.get("slotTimeStart").s());
		map.put("slotTimeEnd", item.get("slotTimeEnd").s());
		return map;
	}

	private APIGatewayProxyResponseEvent buildResponse(int statusCode, String body) {
		Map<String, String> headers = new HashMap<>();
		headers.put("Content-Type", "application/json");
		headers.put("Access-Control-Allow-Origin", "*");
		headers.put("Access-Control-Allow-Headers",
				"Content-Type,X-Amz-Date,Authorization,X-Api-Key,X-Amz-Security-Token");
		headers.put("Access-Control-Allow-Methods", "*");
		headers.put("Accept-Version", "*");

		return new APIGatewayProxyResponseEvent()
				.withStatusCode(statusCode)
				.withHeaders(headers)
				.withBody(body)
				.withIsBase64Encoded(false);
	}
}