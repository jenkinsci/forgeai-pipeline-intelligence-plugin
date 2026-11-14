// The ForgeAI HTML report uses an inline <style> block. Jenkins' default Content-Security-Policy
// for archived files blocks inline styles, so the report would render unstyled. Allow inline
// styles only; scripts stay blocked by the sandbox. Demo setting, review before using elsewhere.
System.setProperty("hudson.model.DirectoryBrowserSupport.CSP",
        "sandbox; default-src 'none'; img-src 'self'; style-src 'self' 'unsafe-inline';")
