package com.morpheusdata.reports

import com.morpheusdata.core.AbstractReportProvider
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.model.OptionType
import com.morpheusdata.model.ReportResult
import com.morpheusdata.model.ReportType
import com.morpheusdata.model.ReportResultRow
import com.morpheusdata.model.ContentSecurityPolicy
import com.morpheusdata.views.HTMLResponse
import com.morpheusdata.views.ViewModel
import com.morpheusdata.response.ServiceResponse
import groovy.sql.GroovyRowResult
import groovy.sql.Sql
import groovy.util.logging.Slf4j
import io.reactivex.Observable;

import java.sql.Connection

/**
 * Report that list total number of users logins per month. Also includes option to list unique user logins per month
 * This report does not take into account user permissions currently and simply uses a direct SQL Connection using Groovy SQL with RxJava to generate a set of results.
 *
 * A renderer is also defined to render the HTML via Handlebars templates.
 *
 * @author Waqas Abbas
 */
 @Slf4j
class CustomReportProvider extends AbstractReportProvider {
	Plugin plugin
	MorpheusContext morpheusContext

	CustomReportProvider(Plugin plugin, MorpheusContext context) {
		this.plugin = plugin
		this.morpheusContext = context
	}

	@Override
	MorpheusContext getMorpheus() {
		morpheusContext
	}

	@Override
	Plugin getPlugin() {
		plugin
	}

	@Override
	String getCode() {
		'custom-report-user-logins'
	}

	@Override
	String getName() {
		'Report Morpheus User Logins Per Month'
	}

	 ServiceResponse validateOptions(Map opts) {
		 return ServiceResponse.success()
	 }

	/**
	 * Demonstrates building a TaskConfig to get details about the Instance and renders the html from the specified template.
	 * @param instance details of an Instance
	 * @return
	 */
	@Override
	HTMLResponse renderTemplate(ReportResult reportResult, Map<String, List<ReportResultRow>> reportRowsBySection) {
		ViewModel<String> model = new ViewModel<String>()
		model.object = reportRowsBySection
		if (reportResult.configMap?.perDayChkbx){
				getRenderer().renderTemplate("hbs/logonPerDayReport", model)
			} else {
				getRenderer().renderTemplate("hbs/logonReport", model)
			}
		
	}

	/**
	 * Allows various sources used in the template to be loaded
	 * @return
	 */
	@Override
	ContentSecurityPolicy getContentSecurityPolicy() {
		def csp = new ContentSecurityPolicy()
		csp.scriptSrc = '*.jsdelivr.net'
		csp.frameSrc = '*.digitalocean.com'
		csp.imgSrc = '*.wikimedia.org'
		csp.styleSrc = 'https: *.bootstrapcdn.com'
		csp
	}


	void process(ReportResult reportResult) {
		morpheus.report.updateReportResultStatus(reportResult,ReportResult.Status.generating).blockingGet();
		Long displayOrder = 0
		List<GroovyRowResult> results = []
		withDbConnection { Connection dbConnection ->

			String uniqueQParam ="u.id" 
			if (reportResult.configMap?.uniqueUserLogonChkbx){ //if unique users checked 
				uniqueQParam ="DISTINCT(u.id)"
			} 
			if(reportResult.configMap?.userRoles) {
				String phraseMatch = "${reportResult.configMap?.userRoles}"
				String qParam =""
				for( String values : phraseMatch.split(',') ){ // create query parameter for mysql statement to skip users that are memeber of specified roles
      				qParam = qParam +"\'"+values+"\',"
				}
				qParam = qParam.substring(0, qParam.length() - 1) //removes the last "," character added by the for loop
				if (reportResult.configMap?.perDayChkbx){ // if user role specified and per day report required
					results = new Sql(dbConnection).rows("select YEAR(al.date_created) as year, MONTHNAME(al.date_created) as month,DAYOFMONTH(al.date_created) as day, count("+uniqueQParam+") as total_users from audit_log as al inner join user as u on u.id = al.user_id where al.event_type like '%login%' and u.id not in (select distinct(ur.user_id) from user_role as ur inner join role r on ur.role_id = r.id where (r.role_type ='user' OR r.role_type IS NULL) and r.authority IN ("+qParam+")) GROUP BY YEAR(al.date_created), MONTHNAME(al.date_created),DAYOFMONTH(al.date_created) ORDER BY YEAR(al.date_created) DESC,FIELD(MONTH,'January','February','March','April','May','June','July','August','September','October','November','December') DESC,DAYOFMONTH(al.date_created) DESC;")
				} else {// if user role specified and monthlty report required
					results = new Sql(dbConnection).rows("select YEAR(al.date_created) as year, MONTHNAME(al.date_created) as month, count("+uniqueQParam+") as total_users from audit_log as al inner join user as u on u.id = al.user_id where al.event_type like '%login%' and u.id not in (select distinct(ur.user_id) from user_role as ur inner join role r on ur.role_id = r.id where (r.role_type ='user' OR r.role_type IS NULL) and r.authority IN ("+qParam+")) GROUP BY YEAR(al.date_created), MONTHNAME(al.date_created) ORDER BY YEAR(al.date_created) DESC,FIELD(MONTH,'January','February','March','April','May','June','July','August','September','October','November','December') DESC;")
				}
			} else { // user role not specified 
				if (reportResult.configMap?.perDayChkbx){// if user roles not specified and per day report required

					results = new Sql(dbConnection).rows("select YEAR(al.date_created) as year, MONTHNAME(al.date_created) as month,DAYOFMONTH(al.date_created) as day, count("+uniqueQParam+") as total_users from audit_log as al inner join user as u on u.id = al.user_id where al.event_type like '%login%' and u.id not in (select distinct(ur.user_id) from user_role as ur inner join role r on ur.role_id = r.id where (r.role_type ='user' OR r.role_type IS NULL) and r.authority IN ('')) GROUP BY YEAR(al.date_created), MONTHNAME(al.date_created),DAYOFMONTH(al.date_created) ORDER BY YEAR(al.date_created) DESC,FIELD(MONTH,'January','February','March','April','May','June','July','August','September','October','November','December') DESC,DAYOFMONTH(al.date_created) DESC;")

				} else { // if user roles not specified and monthly report required

					results = new Sql(dbConnection).rows("select YEAR(al.date_created) as year, MONTHNAME(al.date_created) as month, count("+uniqueQParam+") as total_users from audit_log as al inner join user as u on u.id = al.user_id where al.event_type like '%login%' and u.id not in (select distinct(ur.user_id) from user_role as ur inner join role r on ur.role_id = r.id where (r.role_type ='user' OR r.role_type IS NULL) and r.authority IN ('')) GROUP BY YEAR(al.date_created), MONTHNAME(al.date_created) ORDER BY YEAR(al.date_created) DESC,FIELD(MONTH,'January','February','March','April','May','June','July','August','September','October','November','December') DESC;")
				}
			}
		}

		//log.info("Results: ${results}")
		Observable<GroovyRowResult> observable = Observable.fromIterable(results) as Observable<GroovyRowResult>
		observable.map{ resultRow ->
			//log.info("Mapping resultRow ${resultRow}")
			def Map<String,Object> data = [:]
			if (reportResult.configMap?.perDayChkbx){
				data = [Year: resultRow.year, Month: resultRow.month,Day: resultRow.day, TotalUsers: resultRow.total_users]
			} else {
				data = [Year: resultRow.year, Month: resultRow.month, TotalUsers: resultRow.total_users]
			}
			
			
			ReportResultRow resultRowRecord = new ReportResultRow(section: ReportResultRow.SECTION_MAIN, displayOrder: displayOrder++, dataMap: data)
			//log.info("resultRowRecord: ${resultRowRecord.dump()}")
			return resultRowRecord
		}.buffer(50).doOnComplete {
			morpheus.report.updateReportResultStatus(reportResult,ReportResult.Status.ready).blockingGet();
		}.doOnError { Throwable t ->
			morpheus.report.updateReportResultStatus(reportResult,ReportResult.Status.failed).blockingGet();
		}.subscribe {resultRows ->
			morpheus.report.appendResultRows(reportResult,resultRows).blockingGet()
		}

	}

	 @Override
	 String getDescription() {
		 return "Report to list total number of users per month"
	 }

	 @Override
	 String getCategory() {
		 return 'inventory'
	 }

	 @Override
	 Boolean getOwnerOnly() {
		 return false
	 }

	 @Override
	 Boolean getMasterOnly() {
		 return true
	 }

	 @Override
	 Boolean getSupportsAllZoneTypes() {
		 return true
	 }

	 OptionType userRolesExcludeTextField = new OptionType(
	 										code: 'ignore-user-roles', 
	 										name: 'User Roles', 
	 										fieldName: 'userRoles', 
	 										fieldContext: 'config', 
	 										fieldLabel: 'Ignore Roles', 
	 										displayOrder: 0,
	 										helpBlock:'Optional - Specify a comma separated list of user roles to exclude the role members from the report'
	 										)

	 OptionType uniqueCheckbox = new OptionType(
                                name: 'Unique Logons',
                                code: 'unique-user-logon',
                                fieldName: 'uniqueUserLogonChkbx',
                                
                                displayOrder: 1,
                                fieldLabel: 'Unique Logins',
                                required: false,
                                inputType: OptionType.InputType.CHECKBOX,
                                fieldContext: 'config'
                				)

	 OptionType perDayCheckbox = new OptionType(
                                name: 'Show Per Day',
                                code: 'list-logon-per-day-chkbx',
                                fieldName: 'perDayChkbx',
                                
                                displayOrder: 2,
                                fieldLabel: 'Per Day',
                                required: false,
                                inputType: OptionType.InputType.CHECKBOX,
                                fieldContext: 'config'
                				)



	 @Override
	 List<OptionType> getOptionTypes() {
		 [userRolesExcludeTextField,uniqueCheckbox,perDayCheckbox]
	 }
 }
