package org.keycloak.testsuite.broker;

import org.jboss.arquillian.graphene.page.Page;
import org.junit.Before;
import org.junit.Test;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.admin.client.resource.UserResource;
import org.keycloak.authentication.authenticators.broker.IdpAutoLinkAuthenticatorFactory;
import org.keycloak.authentication.authenticators.browser.OTPFormAuthenticatorFactory;
import org.keycloak.authentication.authenticators.browser.PasswordFormFactory;
import org.keycloak.models.AuthenticationExecutionModel.Requirement;
import org.keycloak.models.AuthenticationFlowModel;
import org.keycloak.models.UserModel;
import org.keycloak.representations.idm.UserRepresentation;
import org.keycloak.testsuite.Assert;
import org.keycloak.testsuite.admin.ApiUtil;
import org.keycloak.testsuite.pages.PasswordPage;
import org.keycloak.testsuite.util.FlowUtil;
import org.keycloak.testsuite.util.UserBuilder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.keycloak.testsuite.broker.BrokerTestTools.waitForPage;

/**
 * Tests first-broker-login flow with new authenticators.
 * <p>
 * Especially for re-authentication of user, which is linking to IDP broker, it uses "Password Form" authenticator instead of default IdpUsernamePasswordForm.
 * It tests various variants with OTP( Conditional OTP, Password-or-OTP) .
 *
 * @author <a href="mailto:mposolda@redhat.com">Marek Posolda</a>
 */
public class KcOidcFirstBrokerLoginNewAuthTest extends AbstractInitializedBaseBrokerTest {

    @Page
    PasswordPage passwordPage;

    @Override
    protected BrokerConfiguration getBrokerConfiguration() {
        return KcOidcBrokerConfiguration.INSTANCE;
    }

    @Before
    public void disableReviewProfileBeforeTest() {
        updateExecutions(AbstractBrokerTest::disableUpdateProfileOnFirstLogin);
    }

    /**
     * Tests the firstBrokerLogin flow configured to re-authenticate with PasswordForm authenticator (Not the form with username/password, but password only)
     * OTP is not configured for the user and hence not requested (There is default OTP conditional subflow used)
     */
    @Test
    public void testReAuthenticateWithPasswordAndConditionalOTP_otpNotRequested() {
        configureBrokerFlowToReAuthenticationWithPasswordForm(bc.getIDPAlias(), "first broker login with password form");

        String consumerRealmUserId = createUser("consumer");
        loginWithBrokerAndConfirmLinkAccount();

        // Assert on the page with password form
        Assert.assertTrue(passwordPage.isCurrent("consumer"));

        // Try bad password first
        passwordPage.login("bad-password");
        Assert.assertEquals("Invalid username or password.", passwordPage.getError());

        // Try good password
        passwordPage.login("password");

        assertUserAuthenticatedInConsumer(consumerRealmUserId);
    }


    /**
     * Tests the firstBrokerLogin flow configured to re-authenticate with PasswordForm authenticator.
     * Assert that OTP is required too as it is configured for the user (There is default OTP conditional subflow used)
     */
    @Test
    public void testReAuthenticateWithPasswordAndConditionalOTP_otpRequested() {
        configureBrokerFlowToReAuthenticationWithPasswordForm(bc.getIDPAlias(), "first broker login with password form");

        // Create user and link him with TOTP
        String consumerRealmUserId = createUser("consumer");
        String totpSecret = addTOTPToUser("consumer");

        loginWithBrokerAndConfirmLinkAccount();

        // Login with password
        Assert.assertTrue(passwordPage.isCurrent("consumer"));
        passwordPage.login("password");

        // Assert on TOTP page. Login with TOTP
        loginTotpPage.assertCurrent();
        loginTotpPage.login(totp.generateTOTP(totpSecret));

        assertUserAuthenticatedInConsumer(consumerRealmUserId);
    }


    /**
     * Tests the firstBrokerLogin flow configured to re-authenticate with PasswordForm OR TOTP.
     * TOTP is not configured for the user and hence he MUST authenticate with password
     */
    @Test
    public void testReAuthenticateWithPasswordOrOTP_otpNotConfigured_passwordUsed() {
        configureBrokerFlowToReAuthenticationWithPasswordOrTotp(bc.getIDPAlias(), "first broker login with password or totp");

        String consumerRealmUserId = createUser("consumer");

        loginWithBrokerAndConfirmLinkAccount();

        // Assert that user can't see credentials combobox. Password is the only available credentials.
        Assert.assertTrue(passwordPage.isCurrent("consumer"));
        passwordPage.assertCredentialsComboboxAvailability(false);

        // Login with password
        Assert.assertTrue(passwordPage.isCurrent("consumer"));
        passwordPage.login("password");

        assertUserAuthenticatedInConsumer(consumerRealmUserId);
    }


    /**
     * Tests the firstBrokerLogin flow configured to re-authenticate with PasswordForm OR TOTP.
     * TOTP is configured for the user and hence he can authenticate with OTP. However he selects password
     */
    @Test
    public void testReAuthenticateWithPasswordOrOTP_otpConfigured_passwordUsed() {
        configureBrokerFlowToReAuthenticationWithPasswordOrTotp(bc.getIDPAlias(), "first broker login with password or totp");

        // Create user and link him with TOTP
        String consumerRealmUserId = createUser("consumer");
        addTOTPToUser("consumer");

        loginWithBrokerAndConfirmLinkAccount();

        // Assert that user can see credentials combobox. Password and OTP are available credentials. Password should be selected.
        Assert.assertTrue(passwordPage.isCurrent("consumer"));
        passwordPage.assertCredentialsComboboxAvailability(true);
        Assert.assertNames(passwordPage.getAvailableCredentials(), "Password", "OTP");
        Assert.assertEquals("Password", passwordPage.getSelectedCredential());

        // Login with password
        Assert.assertTrue(passwordPage.isCurrent("consumer"));
        passwordPage.login("password");

        assertUserAuthenticatedInConsumer(consumerRealmUserId);
    }

    /**
     * Tests the firstBrokerLogin flow configured to re-authenticate with PasswordForm OR TOTP.
     * TOTP is configured for the user and he selects it to authenticate. Password is not used.
     */
    @Test
    public void testReAuthenticateWithPasswordOrOTP_otpConfigured_otpUsed() {
        configureBrokerFlowToReAuthenticationWithPasswordOrTotp(bc.getIDPAlias(), "first broker login with password or totp");

        // Create user and link him with TOTP
        String consumerRealmUserId = createUser("consumer");
        String totpSecret = addTOTPToUser("consumer");

        loginWithBrokerAndConfirmLinkAccount();

        // Assert that user can see credentials combobox. Password and OTP are available credentials. Password should be selected.
        Assert.assertTrue(passwordPage.isCurrent("consumer"));
        passwordPage.assertCredentialsComboboxAvailability(true);

        // Select OTP and assert
        passwordPage.selectCredential("OTP");
        loginTotpPage.assertCurrent();
        Assert.assertEquals("OTP", loginTotpPage.getSelectedCredential());

        // Login with OTP now
        loginTotpPage.login(totp.generateTOTP(totpSecret));

        assertUserAuthenticatedInConsumer(consumerRealmUserId);
    }


    /**
     * Tests the firstBrokerLogin flow configured to re-authenticate with PasswordForm authenticator.
     * Do some testing with back button
     */
    @Test
    public void testBackButtonWithOTPEnabled() {
        configureBrokerFlowToReAuthenticationWithPasswordForm(bc.getIDPAlias(), "first broker login with password form");

        // Create user and link him with TOTP
        String consumerRealmUserId = createUser("consumer");
        String totpSecret = addTOTPToUser("consumer");

        loginWithBrokerAndConfirmLinkAccount();

        // Login with password
        Assert.assertTrue(passwordPage.isCurrent("consumer"));
        passwordPage.login("password");

        // Assert on TOTP page. Assert "Back" button available
        loginTotpPage.assertCurrent();
        loginTotpPage.assertBackButtonAvailability(true);

        // Click "Back" 2 times. Should be on "Confirm account" page
        loginTotpPage.clickBackButton();

        Assert.assertTrue(passwordPage.isCurrent("consumer"));
        passwordPage.assertBackButtonAvailability(true);
        passwordPage.clickBackButton();

        // Back button won't be available on "Confirm Link" page. It was the first authenticator
        idpConfirmLinkPage.assertCurrent();
        idpConfirmLinkPage.assertBackButtonAvailability(false);

        // Authenticate
        idpConfirmLinkPage.clickLinkAccount();

        Assert.assertTrue(passwordPage.isCurrent("consumer"));
        passwordPage.login("password");

        loginTotpPage.assertCurrent();
        loginTotpPage.login(totp.generateTOTP(totpSecret));

        assertUserAuthenticatedInConsumer(consumerRealmUserId);
    }


    // Add OTP to the user. Return TOTP secret
    private String addTOTPToUser(String username) {

        RealmResource realm = adminClient.realm(bc.consumerRealmName());
        UserResource user = ApiUtil.findUserByUsernameId(realm, username);

        // Add CONFIGURE_TOTP requiredAction to the user
        UserRepresentation userRep = UserBuilder.edit(user.toRepresentation()).requiredAction(UserModel.RequiredAction.CONFIGURE_TOTP.toString()).build();
        user.update(userRep);

        // Login. TOTP will be required at login time.
        driver.navigate().to(getAccountUrl(bc.consumerRealmName()));
        loginPage.login(username, "password");

        totpPage.assertCurrent();
        String totpSecret = totpPage.getTotpSecret();
        totpPage.configure(totp.generateTOTP(totpSecret));

        // Logout user through admin endpoint
        user.logout();

        return totpSecret;
    }


    // Login with broker and click "Link account"
    private void loginWithBrokerAndConfirmLinkAccount() {
        driver.navigate().to(getAccountUrl(bc.consumerRealmName()));

        logInWithBroker(bc);

        waitForPage(driver, "account already exists", false);
        assertTrue(idpConfirmLinkPage.isCurrent());
        assertEquals("User with email user@localhost.com already exists. How do you want to continue?", idpConfirmLinkPage.getMessage());
        idpConfirmLinkPage.clickLinkAccount();
    }


    private void assertUserAuthenticatedInConsumer(String consumerRealmUserId) {
        waitForPage(driver, "keycloak account management", true);
        accountUpdateProfilePage.assertCurrent();
        assertNumFederatedIdentities(consumerRealmUserId, 1);
    }


    // Configure the variant of firstBrokerLogin flow, which will use PasswordForm instead of IdpUsernamePasswordForm.
    // In other words, the form with password-only instead of username/password.
    private void configureBrokerFlowToReAuthenticationWithPasswordForm(String idpAlias, String newFlowAlias) {
        testingClient.server(bc.consumerRealmName()).run(session -> FlowUtil.inCurrentRealm(session).copyFirstBrokerLoginFlow(newFlowAlias));
        testingClient.server(bc.consumerRealmName()).run(session -> FlowUtil.inCurrentRealm(session)
                .selectFlow(newFlowAlias)
                .inVerifyExistingAccountByReAuthentication(subFlow -> subFlow
                        // Remove first execution (IdpUsernamePasswordForm)
                        .removeExecution(0)
                        // Edit new first execution (Conditional OTP Subflow)
                        .updateExecution(0, exec -> exec.setPriority(30))
                        // Add AutoLink Authenticator as first (It will automatically setup user to authentication context)
                        .addAuthenticatorExecution(Requirement.REQUIRED, IdpAutoLinkAuthenticatorFactory.PROVIDER_ID, 10)
                        // Add PasswordForm execution
                        .addAuthenticatorExecution(Requirement.REQUIRED, PasswordFormFactory.PROVIDER_ID, 20)
                )
                .usesInIdentityProvider(idpAlias)
        );
    }

    // Configure the variant of firstBrokerLogin flow, which will allow to reauthenticate user with password OR totp
    // TOTP will be available just if configured for the user
    private void configureBrokerFlowToReAuthenticationWithPasswordOrTotp(String idpAlias, String newFlowAlias) {
        testingClient.server(bc.consumerRealmName()).run(session -> FlowUtil.inCurrentRealm(session).copyFirstBrokerLoginFlow(newFlowAlias));
        testingClient.server(bc.consumerRealmName()).run(session -> {
            AuthenticationFlowModel flowModel = FlowUtil.createFlowModel("password or otp", "basic-flow", "Flow to authenticate user with password or otp", false, true);
            FlowUtil.inCurrentRealm(session)
                    // Select new flow
                    .selectFlow(newFlowAlias)
                    .inVerifyExistingAccountByReAuthentication(flowUtil -> flowUtil
                            .clear()
                            // Add AutoLink Authenticator as first (It will automatically setup user to authentication context)
                            .addAuthenticatorExecution(Requirement.REQUIRED, IdpAutoLinkAuthenticatorFactory.PROVIDER_ID)
                            // Add "Password-or-OTP" subflow
                            .addSubFlowExecution(flowModel, Requirement.REQUIRED, subFlow -> subFlow
                                    // Add PasswordForm ALTERNATIVE execution
                                    .addAuthenticatorExecution(Requirement.ALTERNATIVE, PasswordFormFactory.PROVIDER_ID)
                                    // Add OTPForm ALTERNATIVE execution
                                    .addAuthenticatorExecution(Requirement.ALTERNATIVE, OTPFormAuthenticatorFactory.PROVIDER_ID)
                            )
                    )
                    // Setup new FirstBrokerLogin to identity provider
                    .usesInIdentityProvider(idpAlias);
        });
    }
}
