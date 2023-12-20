package business.order;

import api.ApiException;
import business.BookstoreDbException;
import business.JdbcUtils;
import business.book.Book;
import business.book.BookDao;
import business.cart.ShoppingCart;
import business.cart.ShoppingCartItem;
import business.category.Category;
import business.customer.Customer;
import business.customer.CustomerDao;
import business.customer.CustomerForm;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

public class DefaultOrderService implements OrderService {

	private BookDao bookDao;
	private CustomerDao customerDao;
	private OrderDao orderDao;
	private LineItemDao lineItemDao;

	public void setBookDao(BookDao bookDao) {
		this.bookDao = bookDao;
	}

	public void setCustomerDao(CustomerDao customerDao) {
		this.customerDao = customerDao;
	}

	public void setOrderDao(OrderDao orderDao) {
		this.orderDao = orderDao;
	}

	public void setLineItemDao(LineItemDao lineItemDao) {
		this.lineItemDao = lineItemDao;
	}



	@Override
	public OrderDetails getOrderDetails(long orderId) {
		Order order = orderDao.findByOrderId(orderId);
		System.out.println("Customer Id from getOrderDeeds");
		System.out.println(order.customerId());
		Customer customer = customerDao.findByCustomerId(order.customerId());
		List<LineItem> lineItems = lineItemDao.findByOrderId(orderId);
		List<Book> books = lineItems
		.stream()
		.map(lineItem -> bookDao.findByBookId(lineItem.bookId()))
		.toList();
		return new OrderDetails(order, customer, lineItems, books);
	}

	private int generateConfirmationNumber() {
		return ThreadLocalRandom.current().nextInt(999999999);
	}

	private long performPlaceOrderTransaction(
        String name, String address, String phone,
        String email, String ccNumber, Date date,
        ShoppingCart cart, Connection connection) {
    try {
        connection.setAutoCommit(false);
        long customerId = customerDao.create(
                connection, name, address, phone, email,
                ccNumber, date);
        long customerOrderId = orderDao.create(
                connection,
                cart.getComputedSubtotal() + cart.getSurcharge(),
                generateConfirmationNumber(), customerId);
		connection.commit();		
        for (ShoppingCartItem item : cart.getItems()) {
            lineItemDao.create(connection,
                               item.getBookId(), customerOrderId, item.getQuantity());
        }
        connection.commit();
        return customerOrderId;
    } catch (Exception e) {
				StringWriter sw = new StringWriter();
        e.printStackTrace(new PrintWriter(sw));
        String exceptionAsString = sw.toString();
        System.out.println(exceptionAsString);
        try {
            connection.rollback();
        } catch (SQLException e1) {
            throw new BookstoreDbException("Failed to roll back transaction", e1);
        }
        return 0;
			}
		}

		private Date getCardExpirationDate(String monthString, String yearString) throws ParseException {
			int year = Integer.parseInt(yearString);
			int month = Integer.parseInt(monthString);
			LocalDate localDate = LocalDate.of(year, month, 1);
			return Date.from(localDate.atStartOfDay(ZoneId.systemDefault()).toInstant());
		}

	@Override
    public long placeOrder(CustomerForm customerForm, ShoppingCart cart) {

		validateCustomer(customerForm);
		validateCart(cart);

		try (Connection connection = JdbcUtils.getConnection()) {
			Date ccExpDate = getCardExpirationDate(
					customerForm.getCcExpiryMonth(),
					customerForm.getCcExpiryYear());
			return performPlaceOrderTransaction(
					customerForm.getName(),
					customerForm.getAddress(),
					customerForm.getPhone(),
					customerForm.getEmail(),
					customerForm.getCcNumber(),
					ccExpDate, cart, connection);
		} catch (SQLException e) {
			throw new BookstoreDbException("Error during close connection for customer order", e);
		} catch (ParseException e) {
			throw new BookstoreDbException("Error during parsing date", e);
		} catch (Exception e) {
			throw new BookstoreDbException("Error during place order transaction", e);
		}
	}

	private boolean isPhoneValid(String phone) {
		String cleanedNumber = phone.replaceAll("[^0-9]", "");

		if (cleanedNumber.length() == 10) {
				return true;
		} else {
				System.out.println("Invalid phone number: " + phone);
				return false;
		}
	}

	private boolean isEmailValid(String email) {
		if (email.contains(" ")) {
				return false;
		}

		if (!email.contains("@")) {
				return false;
		}

		if (email.endsWith(".")) {
				return false;
		} return true;
	}

	private boolean isCreditCardValid(String ccNumber) {
		String cleanedNumber = ccNumber.replaceAll("\\s", "");

		int length = cleanedNumber.length();
		if (length >= 14 && length <= 16) {
				return true;
		} else {
				return false;
		}
	}


	private void validateCustomer(CustomerForm customerForm) {

    	String name = customerForm.getName();

		if (name == null || name.equals("") || name.length() > 45) {
			throw new ApiException.ValidationFailure("Invalid name field");
		}

		if (customerForm.getAddress() == null || customerForm.getAddress().equals("") || customerForm.getAddress().length() > 45) {
			throw new ApiException.ValidationFailure("Invalid address field");
		}

		// check if phone is valid. after removing spaces, dashes and patterns like (123) 456-7890 it must be 10 digits
		String phone = customerForm.getPhone();
		if (phone == null || phone.equals("") || isPhoneValid(phone) == false) {
			throw new ApiException.ValidationFailure("Invalid phone field");
		}
		
		String email = customerForm.getEmail();
		if (email == null || email.equals("") || isEmailValid(email) == false) {
			throw new ApiException.ValidationFailure("Invalid email field");
		}

		// TODO: Validation checks for address, phone, email, ccNumber

		String ccNumber = customerForm.getCcNumber();

		if (ccNumber == null || ccNumber.equals("") || isCreditCardValid(ccNumber) == false) {
			throw new ApiException.ValidationFailure("Invalid credit card number");
		}


		if (expiryDateIsInvalid(customerForm.getCcExpiryMonth(), customerForm.getCcExpiryYear())) {
			throw new ApiException.ValidationFailure("Invalid expiry date");

		}
	}

	private boolean expiryDateIsInvalid(String ccExpiryMonth, String ccExpiryYear) {

		if (ccExpiryMonth == null || ccExpiryYear == null || ccExpiryMonth.equals("") || ccExpiryYear.equals("")) {
			return false;
		}
		
		int month = Integer.parseInt(ccExpiryMonth);

		if (month < 1 || month > 12) {
			return false;
		}
		
		if (Integer.parseInt(ccExpiryMonth) < 10) {
			ccExpiryMonth = "0" + ccExpiryMonth;
		}

		String inputMonthYear = ccExpiryMonth + "/" + ccExpiryYear;

		DateTimeFormatter formatter = DateTimeFormatter.ofPattern("MM/yyyy");
		YearMonth currentMonthYear = YearMonth.now();
		YearMonth inputMonthYearObj = YearMonth.parse(inputMonthYear, formatter);

		return inputMonthYearObj.isBefore(currentMonthYear);
	}


	private void validateCart(ShoppingCart cart) {

		if (cart.getItems().size() <= 0) {
			throw new ApiException.ValidationFailure("Cart is empty.");
		}

		cart.getItems().forEach(item-> {
			if (item.getQuantity() < 0 || item.getQuantity() > 99) {
				throw new ApiException.ValidationFailure("Invalid quantity");
			}
			Book databaseBook = bookDao.findByBookId(item.getBookId());
			List<Book> databaseCategory = bookDao.findByCategoryId(item.getBookForm().getCategoryId());

			if (databaseCategory == null) {
				throw new ApiException.ValidationFailure("Invalid Category");
			}

			if (databaseBook.price() != item.getBookForm().getPrice()) {
				throw new ApiException.ValidationFailure("Invalid price");
			}



			// TODO: complete the required validations
		});
	}

}
